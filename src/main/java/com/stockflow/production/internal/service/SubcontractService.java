package com.stockflow.production.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.contracts.GoodsReceiptConfirmed;
import com.stockflow.contracts.ProductionOrderHeld;
import com.stockflow.inventory.api.HoldReceivedStockCommand;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveProductionStockCommand;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.procurement.api.CreateSubcontractOrderCommand;
import com.stockflow.procurement.api.SubcontractOrder;
import com.stockflow.procurement.api.SubcontractPurchasing;
import com.stockflow.procurement.api.SubcontractSettlement;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.ProductionRecords;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link Subcontracting} on the parent's or the child's row lock. The split, the child, its SUBCONTRACT PO and
 * the blanks' holds commit together: a PO the procurement module refuses (not a print subcontractor) leaves no
 * child behind, and no blank is let go for a split that did not happen. Every end of a subcontracted order — its
 * completion, its cancellation, its replacement in house — closes its PO for production and settles it in the same
 * transaction ({@link SubcontractSettling}).
 */
@Service
@Transactional
class SubcontractService implements Subcontracting {

    private static final Logger log = LoggerFactory.getLogger(SubcontractService.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final ProductionOrderRepository orders;
    private final ProductionRecords records;
    private final ProductionOrders reads;
    private final SubcontractPurchasing purchasing;
    private final InventoryService inventory;
    private final OrderService salesOrders;
    private final WarehouseService warehouses;
    private final WorkshopStock stock;
    private final ProductionCompletion completion;
    private final SubcontractSettling settling;
    private final ProductionWorkflow workflow;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    SubcontractService(ProductionOrderRepository orders, ProductionRecords records, ProductionOrders reads,
                       SubcontractPurchasing purchasing, InventoryService inventory, OrderService salesOrders,
                       WarehouseService warehouses, WorkshopStock stock, ProductionCompletion completion,
                       SubcontractSettling settling, ProductionWorkflow workflow, ApplicationEventPublisher events,
                       Clock clock) {
        this.stock = stock;
        this.orders = orders;
        this.records = records;
        this.reads = reads;
        this.purchasing = purchasing;
        this.inventory = inventory;
        this.salesOrders = salesOrders;
        this.warehouses = warehouses;
        this.completion = completion;
        this.settling = settling;
        this.workflow = workflow;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "production-order", resourceId = "#parentId")
    public ProductionOrders.Detail split(CurrentUser user, UUID parentId, Split split) {
        if (split == null || split.method() == null || split.subcontractorId() == null || split.unitPrice() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A split names its quantity, subcontractor, method and unit price");
        }
        ProductionOrder parent = lock(user, parentId);
        // BR-PRD-13 before anything is written: the child's row names its subcontractor.
        var terms = purchasing.subcontractorTerms(split.subcontractorId()).orElseThrow(() -> new BusinessException(
                ErrorCode.SUPPLIER_NOT_FOUND, "No supplier " + split.subcontractorId()));
        if (!terms.printSubcontractor()) {
            throw new BusinessException(ErrorCode.SUPPLIER_NOT_SUBCONTRACTOR,
                    "%s does not print for us".formatted(terms.name()));
        }
        Instant now = now();
        if (parent.wholeOrder(split.quantity())) {
            return convert(user, parent, split, now);
        }
        String childNumber = orders.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE));
        ProductionOrder.Transition splitOff = parent.splitOff(split.quantity(), childNumber);
        ProductionOrder child = ProductionOrder.subcontractedChild(Identifiers.newId(), childNumber, parent,
                split.quantity(), split.dueDate() != null ? split.dueDate() : parent.work().dueDate(),
                new ProductionOrder.Subcontract(split.method(), split.subcontractorId(), split.unitPrice(),
                        split.currency() == null ? "VND" : split.currency(), null));
        orders.save(parent);
        orders.recordTransition(parent.id(), splitOff, now, user.userId());
        orders.createChild(child, user.userId(), now);
        SubcontractOrder po = purchasing.create(new CreateSubcontractOrderCommand(child.id(), split.subcontractorId(),
                child.work().warehouseId(), child.work().blankSku(), split.quantity(), split.unitPrice(),
                child.subcontract().currency(), child.work().dueDate(), user.userId()));
        child.attachPurchaseOrder(po.purchaseOrderId());
        orders.save(child);
        moveHolds(parent, child, split.quantity());
        log.info("Production order {} split {} to {} ({}, {}), PO {}", parent.orderNumber(), split.quantity(),
                child.orderNumber(), split.method(), split.subcontractorId(), po.poNumber());
        return reads.get(user, child.id());
    }

    /**
     * BR-PRD-12, the whole order: it becomes subcontracted itself (same row and number, no child), gets its DRAFT
     * SUBCONTRACT PO, and its blanks are let go or held for it as a split's would be — in one transaction.
     */
    private ProductionOrders.Detail convert(CurrentUser user, ProductionOrder order, Split split, Instant now) {
        ProductionOrder.Transition converted = order.convertToSubcontract(new ProductionOrder.Subcontract(
                split.method(), split.subcontractorId(), split.unitPrice(),
                split.currency() == null ? "VND" : split.currency(), null));
        // Saved before the PO: check_subcontract_po wants a SUBCONTRACTED production order to point at.
        orders.save(order);
        orders.recordTransition(order.id(), converted, now, user.userId());
        SubcontractOrder po = purchasing.create(new CreateSubcontractOrderCommand(order.id(), split.subcontractorId(),
                order.work().warehouseId(), order.work().blankSku(), split.quantity(), split.unitPrice(),
                order.subcontract().currency(), split.dueDate() != null ? split.dueDate() : order.work().dueDate(),
                user.userId()));
        order.attachPurchaseOrder(po.purchaseOrderId());
        orders.save(order);
        moveHolds(order, order, split.quantity());
        log.info("Production order {} converted whole to subcontracting ({}, {}), PO {}", order.orderNumber(),
                split.method(), split.subcontractorId(), po.poNumber());
        return reads.get(user, order.id());
    }

    /**
     * G3: the order's blanks for the split quantity, taken from its holds not issued yet (latest first), are let
     * go — FULL_SERVICE, the subcontractor brings its own — or, SUPPLIED_BLANKS, held for the child in their
     * place, in the same transaction, so the order's total held never drops for a moment.
     */
    private void moveHolds(ProductionOrder parent, ProductionOrder child, int quantity) {
        Set<UUID> issued = records.issuesOf(parent.id()).stream()
                .map(ProductionRecords.MaterialIssue::reservationId).collect(Collectors.toSet());
        // The line's checkout holds are the root order's; a child of the line works on its own holds only.
        List<UUID> lineHolds = parent.parentId() != null ? new ArrayList<>()
                : salesOrders.findById(parent.salesOrderId()).orElseThrow().lines().stream()
                .filter(line -> line.lineId().equals(parent.salesOrderLineId()))
                .flatMap(line -> line.reservationIds().stream())
                .filter(id -> !issued.contains(id)).collect(Collectors.toCollection(ArrayList::new));
        lineHolds.addAll(inventory.productionHolds(parent.id()).stream().map(StockReservation::reservationId)
                .filter(id -> !issued.contains(id)).toList());
        List<StockReservation> live = new ArrayList<>(inventory.liveHolds(lineHolds));
        Collections.reverse(live);
        int remaining = quantity;
        for (StockReservation hold : live) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(hold.quantity(), remaining);
            inventory.reduceHold(hold.reservationId(), parent.salesOrderId(), parent.id(), take);
            remaining -= take;
        }
        if (child.subcontract().method() == ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS) {
            String warehouse = warehouses.findWarehouse(child.work().warehouseId()).orElseThrow().prefix();
            inventory.reserveForProduction(new ReserveProductionStockCommand(
                    UUID.nameUUIDFromBytes(("subcontract-blanks:" + child.id()).getBytes(StandardCharsets.UTF_8)),
                    child.work().blankSku(), quantity, child.id(), warehouse));
        }
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#childId")
    public ProductionOrders.Detail sendOut(CurrentUser user, UUID childId) {
        ProductionOrder child = lock(user, childId);
        requireSubcontracted(child);
        SubcontractOrder po = purchasing.findById(child.subcontract().purchaseOrderId()).orElseThrow();
        if (!po.approved()) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Subcontract PO %s is %s; the work goes out once it is approved (BR-PRD-13)"
                            .formatted(po.poNumber(), po.status()));
        }
        if (po.pendingQuantity() != null) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Subcontract PO %s has an amendment to %d waiting for approval; the work goes out once it is decided"
                            .formatted(po.poNumber(), po.pendingQuantity()));
        }
        if (po.openQuantity() < child.stillOwed()) {
            // 03 §16 "PO supplement": a resend after a QC shortfall the approved order does not cover.
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Subcontract PO %s allows %d more unit(s) to be received and %d are owed: supplement it by %d first"
                            .formatted(po.poNumber(), po.openQuantity(), child.stillOwed(),
                                    child.stillOwed() - po.openQuantity()));
        }
        Instant now = now();
        // The transition first: it refuses a child that is not READY, so a second dispatch sends nothing.
        ProductionOrder.Transition sent = child.sendToSubcontractor();
        if (child.subcontract().method() == ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS) {
            sendBlanks(child, user.userId(), now);
        }
        orders.save(child);
        orders.recordTransition(child.id(), sent, now, user.userId());
        log.info("Production order {} sent to subcontractor {} (PO {})", child.orderNumber(),
                child.subcontract().subcontractorId(), po.poNumber());
        return reads.get(user, child.id());
    }

    /**
     * G5, decision Q7: the blanks still missing at the subcontractor ({@code planned − good − already there}) are
     * held for the child if its holds do not cover them, and every hold not sent yet goes to the subcontractor's
     * location for the child's warehouse, created on first use.
     */
    private void sendBlanks(ProductionOrder child, UUID by, Instant now) {
        Set<UUID> notFree = records.issuesOf(child.id()).stream().map(ProductionRecords.MaterialIssue::reservationId)
                .collect(Collectors.toCollection(java.util.HashSet::new));
        notFree.addAll(records.subcontractHoldsOf(child.id()));
        List<StockReservation> unsent = inventory.productionHolds(child.id()).stream()
                .filter(hold -> !notFree.contains(hold.reservationId())).toList();
        int missing = child.work().plannedQuantity() - child.good() - stock.blanksAtSubcontractor(child)
                - unsent.stream().mapToInt(StockReservation::quantity).sum();
        List<UUID> holds = new ArrayList<>(unsent.stream().map(StockReservation::reservationId).toList());
        if (missing < 0) {
            // Part of the plan went back to the print shop before the blanks left: the surplus is let go, latest
            // hold first, so only what the subcontractor still owes goes out.
            int surplus = -missing;
            List<StockReservation> latestFirst = new ArrayList<>(unsent);
            Collections.reverse(latestFirst);
            for (StockReservation hold : latestFirst) {
                if (surplus == 0) {
                    break;
                }
                int take = Math.min(hold.quantity(), surplus);
                inventory.reduceHold(hold.reservationId(), null, child.id(), take);
                if (take == hold.quantity()) {
                    holds.remove(hold.reservationId());
                }
                surplus -= take;
            }
        }
        if (missing > 0) {
            String warehouse = warehouses.findWarehouse(child.work().warehouseId()).orElseThrow().prefix();
            int round = records.issuesOf(child.id()).size();
            inventory.reserveForProduction(new ReserveProductionStockCommand(
                    UUID.nameUUIDFromBytes(("subcontract-blanks:" + child.id() + ":" + round)
                            .getBytes(StandardCharsets.UTF_8)),
                    child.work().blankSku(), missing, child.id(), warehouse)).reservations()
                    .forEach(hold -> holds.add(hold.reservationId()));
        }
        var terms = purchasing.subcontractorTerms(child.subcontract().subcontractorId()).orElseThrow();
        String location = warehouses.subcontractorLocation(child.work().warehouseId(), terms.supplierId(),
                terms.code()).locationCode();
        int sent = stock.sendToSubcontractor(child, location, holds, by, now);
        log.info("Production order {}: {} blank(s) sent to {}", child.orderNumber(), sent, location);
    }

    /**
     * G9: a goods receipt of a child's SUBCONTRACT PO puts what it counted in front of QC, once per receipt (a
     * redelivered event, a second part of the same PO). Receipts of other purchase orders are not this module's.
     *
     * <p>In the receipt's own transaction (decision Q8): each line's goods are held for the child the moment they
     * are counted and wait in the QUALITY_CONTROL area for the one production QC (user decision 2026-10-13: no
     * receiving QC of the same goods), so they are never free stock a putaway could sell; for SUPPLIED_BLANKS the
     * blanks they were printed on leave the subcontractor's location (decision Q9). A failure here fails the
     * confirmation.</p>
     */
    public void onReceipt(GoodsReceiptConfirmed receipt) {
        if (receipt.purchaseOrderId() == null) {
            return;
        }
        Objects.requireNonNull(receipt.receiptId(), "receiptId");
        orders.idOfPurchaseOrder(receipt.purchaseOrderId()).ifPresent(childId -> {
            ProductionOrder child = orders.findByIdForUpdate(childId).orElseThrow();
            List<GoodsReceiptConfirmed.Line> lines = receipt.lines().stream()
                    .filter(line -> line.sku().equals(child.work().blankSku().code())).toList();
            int quantity = lines.stream().mapToInt(GoodsReceiptConfirmed.Line::quantity).sum();
            boolean suppliedBlanks = child.subcontract().method() == ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS;
            Instant now = now();
            if (quantity < 1 || !records.recordSubcontractReceipt(receipt.receiptId(), child.id(), quantity,
                    suppliedBlanks ? quantity : null, now)) {
                return;
            }
            Objects.requireNonNull(receipt.confirmedAt(), "confirmedAt");
            if (suppliedBlanks) {
                stock.consumeAtSubcontractor(child, quantity, receipt.receiptNumber(), null);
            }
            for (GoodsReceiptConfirmed.Line line : lines) {
                StockReservation hold = inventory.holdReceived(new HoldReceivedStockCommand(
                        UUID.nameUUIDFromBytes(("subcontract-receipt-line:" + line.receiptLineId())
                                .getBytes(StandardCharsets.UTF_8)),
                        child.id(), child.work().blankSku(), line.lotNumber(), line.locationCode(),
                        receipt.confirmedAt(), line.quantity()));
                records.recordSubcontractHold(hold.reservationId(), receipt.receiptId(), line.receiptLineId(), child.id());
                stock.holdForProductionQc(child, hold.reservationId(), null);
            }
            ProductionOrder.Transition received = child.receivedFromSubcontractor(quantity);
            orders.save(child);
            orders.recordTransition(child.id(), received, now, null);
            log.info("Production order {}: {} received from the subcontractor on {}", child.orderNumber(), quantity,
                    receipt.receiptNumber());
        });
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "production-subcontract-receipt", resourceId = "#receiptId")
    public ProductionOrders.Detail reconcile(CurrentUser user, UUID childId, UUID receiptId, int spoilt, int returned,
                                             String returnLocation) {
        if (receiptId == null || spoilt < 0 || returned < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A reconciliation names its receipt; spoilt and returned cannot be negative");
        }
        ProductionOrder child = lock(user, childId);
        requireSuppliedBlanks(child);
        ProductionRecords.SubcontractReceipt receipt = records.findSubcontractReceiptForUpdate(receiptId)
                .filter(found -> found.productionOrderId().equals(child.id()))
                .orElseThrow(() -> new NotFoundException("production-subcontract-receipt", receiptId));
        if (receipt.reconciled()) {
            if (receipt.spoiltBlanks() == spoilt && receipt.returnedBlanks() == returned) {
                return reads.get(user, child.id());   // a retry of the same reconciliation
            }
            throw new BusinessException(ErrorCode.CONFLICT, "Receipt %s was reconciled already: %d spoilt, %d returned"
                    .formatted(receiptId, receipt.spoiltBlanks(), receipt.returnedBlanks()));
        }
        requireReturnLocation(child, returned, returnLocation);
        Instant now = now();
        stock.reconcile(child, spoilt, returned, returnLocation, "receipt " + receiptId, user.userId());
        records.reconcileSubcontractReceipt(receiptId, spoilt, returned, now, user.userId());
        log.info("Production order {}: receipt {} reconciled, {} received, {} spoilt, {} returned, {} still at the "
                        + "subcontractor", child.orderNumber(), receiptId, receipt.quantity(), spoilt, returned,
                stock.blanksAtSubcontractor(child));
        return reads.get(user, child.id());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductionRecords.SubcontractReceipt> receipts(CurrentUser user, UUID childId) {
        reads.get(user, childId);   // 404 and warehouse scope, as for the order itself
        return records.subcontractReceiptsOf(childId);
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "production-order", resourceId = "#childId")
    public ProductionOrders.Detail recall(CurrentUser user, UUID childId, Recall recall) {
        if (recall == null || recall.requestId() == null || recall.returned() < 0 || recall.spoilt() < 0
                || recall.returned() + recall.spoilt() < 1) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A recall names its request id and brings back at least one blank, returned or spoilt");
        }
        if (replayedRecall(childId, recall)) {
            return reads.get(user, childId);
        }
        ProductionOrder child = lock(user, childId);
        if (replayedRecall(childId, recall)) {
            return reads.get(user, childId);   // the first attempt committed while this one waited for the lock
        }
        requireSuppliedBlanks(child);
        requireReturnLocation(child, recall.returned(), recall.returnLocation());
        String note = blankToNull(recall.note());
        if (note != null && note.length() > 1000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A note is at most 1000 characters");
        }
        Instant now = now();
        stock.reconcile(child, recall.spoilt(), recall.returned(), recall.returnLocation(),
                "recall " + recall.requestId(), user.userId());
        records.recordRecall(new ProductionRecords.SubcontractRecall(Identifiers.newId(), child.id(), recall.requestId(),
                recall.returned(), recall.spoilt(), blankToNull(recall.returnLocation()), note, now, user.userId()));
        log.info("Production order {}: {} blank(s) recalled from the subcontractor, {} spoilt; {} still there",
                child.orderNumber(), recall.returned(), recall.spoilt(), stock.blanksAtSubcontractor(child));
        return reads.get(user, child.id());
    }

    private boolean replayedRecall(UUID childId, Recall recall) {
        return records.findRecall(recall.requestId()).map(done -> {
            if (!done.productionOrderId().equals(childId) || done.returned() != recall.returned()
                    || done.spoilt() != recall.spoilt()) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "Request %s already recalled %d returned and %d spoilt blank(s) for production order %s"
                                .formatted(recall.requestId(), done.returned(), done.spoilt(), done.productionOrderId()));
            }
            return true;
        }).orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductionRecords.SubcontractRecall> recalls(CurrentUser user, UUID childId) {
        reads.get(user, childId);
        return records.recallsOf(childId);
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "production-order", resourceId = "#childId")
    public ProductionOrders.Detail takeInHouse(CurrentUser user, UUID childId, int quantity, String reason,
                                               String packingLocation) {
        ProductionOrder child = lock(user, childId);
        requireSubcontracted(child);
        String text = requireReason(reason);
        ProductionOrder inHouse = takeInHouseLocked(child, quantity, text, packingLocation, null, null, user.userId());
        return reads.get(user, inHouse.id());
    }

    /**
     * The in-house order is created READY (or PENDING_PREPRESS) with its blanks held now; too few in the warehouse
     * and it goes ON_HOLD (BLANKS_SHORT) and purchasing hears of it, as for a reprint.
     */
    private ProductionOrder takeInHouseLocked(ProductionOrder child, int quantity, String reason, String packingLocation,
                                              BigDecimal fee, String feeReason, UUID by) {
        boolean replaced = quantity == child.work().plannedQuantity();
        boolean ending = replaced || quantity == child.stillOwed() && child.awaitingQc() == 0;
        if (ending) {
            settling.requireBlanksAccountedFor(child);
        }
        Instant now = now();
        String number = orders.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE));
        ProductionOrder.Transition given = child.giveBackToPrintShop(quantity, number, now);
        ProductionOrder inHouse = ProductionOrder.inHouseFor(Identifiers.newId(), number, child, quantity,
                child.status() == ProductionOrder.Status.CANCELLED ? ProductionOrder.Origin.REPLACEMENT
                        : ProductionOrder.Origin.SHORTFALL);
        orders.save(child);
        orders.recordTransition(child.id(), given, now, by);
        orders.createChild(inHouse, by, now);
        if (child.status() == ProductionOrder.Status.CANCELLED) {
            stock.releaseUnissued(child);
            settling.settle(child, "%s: %s".formatted(given.reason(), reason), fee, feeReason, by);
        } else if (child.status() == ProductionOrder.Status.COMPLETED) {
            completion.complete(child, packingLocation, by);
        }
        if (!stock.holdForReprint(inHouse, quantity)) {
            ProductionOrder.Transition held = inHouse.hold(ProductionOrder.HoldReason.BLANKS_SHORT,
                    "%d blank(s) of %s needed for work taken back from a subcontractor".formatted(quantity,
                            inHouse.work().blankSku()), now);
            orders.save(inHouse);
            orders.recordTransition(inHouse.id(), held, now, by);
            events.publishEvent(new ProductionOrderHeld(inHouse.id(), inHouse.orderNumber(),
                    inHouse.holdReason().name(), inHouse.holdNote(), null, inHouse.salesOrderId()));
        }
        log.info("Production order {}: {} unit(s) taken back to the print shop as {} ({}); it is now {}",
                child.orderNumber(), quantity, inHouse.orderNumber(), reason, child.status());
        return inHouse;
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "production-order", resourceId = "#productionOrderId")
    public ProductionOrders.Detail cancel(CurrentUser user, UUID productionOrderId, String reason, BigDecimal fee,
                                          String feeReason) {
        ProductionOrder order = lock(user, productionOrderId);
        requireSubcontracted(order);
        String text = requireReason(reason);
        BigDecimal agreed = fee == null ? BigDecimal.ZERO : fee;
        String agreedWhy = blankToNull(feeReason);
        if (agreed.signum() < 0 || agreed.stripTrailingZeros().scale() > 2 || agreed.signum() > 0 && agreedWhy == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A subcontractor fee is an amount of at most 2 decimals, not negative, and says why");
        }
        if (!salesOrderCancelled(order)) {
            if (order.good() > 0) {
                throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                        ("Production order %s has %d good unit(s) for its order: take the %d the subcontractor still "
                                + "owes in house instead").formatted(order.orderNumber(), order.good(), order.stillOwed()));
            }
            takeInHouseLocked(order, order.work().plannedQuantity(), text, null, agreed, agreedWhy, user.userId());
            return reads.get(user, order.id());
        }
        settling.requireBlanksAccountedFor(order);
        Instant now = now();
        ProductionOrder.Status from = order.status();
        int unsold = order.cancelSubcontracted(text, now);
        if (unsold > 0) {
            stock.writeOff(order, List.of(new WorkshopStock.Spoilt(ProductionRecords.ScrapReason.OTHER, unsold,
                    "Delivered for a cancelled order: " + text)), user.userId(), now);
        }
        stock.releaseUnissued(order);
        orders.save(order);
        orders.recordTransition(order.id(), new ProductionOrder.Transition(from, ProductionOrder.Status.CANCELLED, text),
                now, user.userId());
        settling.settle(order, text, agreed, agreedWhy, user.userId());
        log.info("Subcontracted production order {} cancelled: {}; {} good unit(s) written off, fee {}",
                order.orderNumber(), text, unsold, agreed);
        return reads.get(user, order.id());
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "production-order", resourceId = "#childId")
    public ProductionOrders.Detail supplement(CurrentUser user, UUID childId, int quantity, String reason) {
        ProductionOrder child = lock(user, childId);
        requireSubcontracted(child);
        if (child.status() == ProductionOrder.Status.COMPLETED || child.status() == ProductionOrder.Status.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Production order %s is %s".formatted(child.orderNumber(), child.status()));
        }
        SubcontractOrder po = purchasing.supplement(child.subcontract().purchaseOrderId(), quantity,
                requireReason(reason), user.userId());
        log.info("Production order {}: PO {} supplemented by {} ({})", child.orderNumber(), po.poNumber(), quantity,
                po.pendingQuantity() == null ? "resized" : "amendment waiting for approval");
        return reads.get(user, child.id());
    }

    @Override
    @Transactional(readOnly = true)
    public SubcontractSettlement settlement(CurrentUser user, UUID productionOrderId) {
        reads.get(user, productionOrderId);
        ProductionOrder order = orders.findById(productionOrderId).orElseThrow();
        requireSubcontracted(order);
        return purchasing.settlement(order.subcontract().purchaseOrderId()).orElseThrow(() ->
                new NotFoundException("subcontract-settlement", productionOrderId));
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "production-order", resourceId = "#productionOrderId")
    public SubcontractSettlement approveLoss(CurrentUser user, UUID productionOrderId, BigDecimal deductionPerBlank,
                                             String note) {
        ProductionOrder order = lock(user, productionOrderId);
        requireSubcontracted(order);
        return purchasing.approveLoss(order.subcontract().purchaseOrderId(), deductionPerBlank, note, user.userId());
    }

    @Override
    @Transactional(readOnly = true)
    public DispatchNote dispatchNote(CurrentUser user, UUID childId) {
        reads.get(user, childId);
        ProductionOrder child = orders.findById(childId).orElseThrow();
        requireSubcontracted(child);
        if (child.prepressCheckedAt() == null) {
            // BR-PRD-14: what goes out is the prepress-checked file.
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Production order %s has not passed prepress".formatted(child.orderNumber()));
        }
        SubcontractOrder po = purchasing.findById(child.subcontract().purchaseOrderId()).orElseThrow();
        var terms = purchasing.subcontractorTerms(child.subcontract().subcontractorId()).orElseThrow();
        List<SentBlanks> blanks = records.issuesOf(child.id()).stream()
                .map(issue -> new SentBlanks(issue.sku(), issue.lotNumber(), issue.fromLocation(), issue.toLocation(),
                        issue.quantity(), issue.issuedAt()))
                .toList();
        return new DispatchNote(child.id(), child.orderNumber(), child.status(), child.subcontract().method().name(),
                po.purchaseOrderId(), po.poNumber(), po.status(), terms.supplierId(), terms.code(), terms.name(),
                child.work().blankSku().code(), child.work().plannedQuantity(), child.work().dueDate(),
                po.expectedDate(), child.work().designSnapshotId(), child.work().designChecksum(),
                workflow.printFile(user, child.id()), child.approvedSampleId(), child.prepressCheckedAt(), blanks);
    }

    private void requireReturnLocation(ProductionOrder child, int returned, String returnLocation) {
        if (returned > 0 && returnLocation != null && !returnLocation.isBlank()) {
            warehouses.findLocation(returnLocation)
                    .filter(location -> location.warehouseId().equals(child.work().warehouseId()))
                    .filter(location -> location.kind() != StorageLocationView.Kind.SUBCONTRACTOR)
                    .orElseThrow(() -> new BusinessException(ErrorCode.LOCATION_NOT_FOUND,
                            "No storage location %s in the warehouse of production order %s".formatted(returnLocation,
                                    child.orderNumber())));
        }
    }

    private static void requireSubcontracted(ProductionOrder order) {
        if (order.mode() != ProductionOrder.Mode.SUBCONTRACTED) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Production order %s is printed in house".formatted(order.orderNumber()));
        }
    }

    private static void requireSuppliedBlanks(ProductionOrder order) {
        if (order.mode() != ProductionOrder.Mode.SUBCONTRACTED
                || order.subcontract().method() != ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Production order %s sent no blanks to a subcontractor".formatted(order.orderNumber()));
        }
    }

    private static String requireReason(String reason) {
        String text = blankToNull(reason);
        if (text == null || text.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A reason is required, at most 500 characters");
        }
        return text;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private boolean salesOrderCancelled(ProductionOrder order) {
        return salesOrders.findById(order.salesOrderId()).map(OrderSummary::status)
                .map(status -> status == OrderStatus.CANCELLED).orElse(true);
    }

    private ProductionOrder lock(CurrentUser user, UUID productionOrderId) {
        ScopeGuards.requireBackOffice(user);
        ProductionOrder order = orders.findByIdForUpdate(Objects.requireNonNull(productionOrderId, "productionOrderId"))
                .orElseThrow(() -> new NotFoundException("production-order", productionOrderId));
        WarehouseScope.requireWarehouse(order.work().warehouseId());
        return order;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
