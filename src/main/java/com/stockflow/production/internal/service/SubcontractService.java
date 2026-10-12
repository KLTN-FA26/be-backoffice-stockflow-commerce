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
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.ReserveProductionStockCommand;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.order.api.OrderService;
import com.stockflow.procurement.api.CreateSubcontractOrderCommand;
import com.stockflow.procurement.api.SubcontractOrder;
import com.stockflow.procurement.api.SubcontractPurchasing;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.ProductionRecords;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * child behind, and no blank is let go for a split that did not happen.
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
    private final Clock clock;

    SubcontractService(ProductionOrderRepository orders, ProductionRecords records, ProductionOrders reads,
                       SubcontractPurchasing purchasing, InventoryService inventory, OrderService salesOrders,
                       WarehouseService warehouses, Clock clock) {
        this.orders = orders;
        this.records = records;
        this.reads = reads;
        this.purchasing = purchasing;
        this.inventory = inventory;
        this.salesOrders = salesOrders;
        this.warehouses = warehouses;
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
     * G3: the order's blanks for the split quantity, taken from its holds not issued yet (latest first), are let
     * go — FULL_SERVICE, the subcontractor brings its own — or, SUPPLIED_BLANKS, held for the child in their
     * place, in the same transaction, so the order's total held never drops for a moment.
     */
    private void moveHolds(ProductionOrder parent, ProductionOrder child, int quantity) {
        Set<UUID> issued = records.issuesOf(parent.id()).stream()
                .map(ProductionRecords.MaterialIssue::reservationId).collect(Collectors.toSet());
        List<UUID> lineHolds = salesOrders.findById(parent.salesOrderId()).orElseThrow().lines().stream()
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
        if (child.mode() != ProductionOrder.Mode.SUBCONTRACTED) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Production order %s is printed in house".formatted(child.orderNumber()));
        }
        SubcontractOrder po = purchasing.findById(child.subcontract().purchaseOrderId()).orElseThrow();
        if (!po.approved()) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Subcontract PO %s is %s; the work goes out once it is approved (BR-PRD-13)"
                            .formatted(po.poNumber(), po.status()));
        }
        if (child.subcontract().method() == ProductionOrder.SubcontractMethod.SUPPLIED_BLANKS) {
            // BR-PRD-15 wants the blanks at a virtual location of the subcontractor, outside ATP. No such location
            // exists yet (open question Q7); the blanks stay held for the child in storage until it does.
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Sending blanks to a subcontractor needs its stock location, which is not set up yet");
        }
        Instant now = now();
        ProductionOrder.Transition sent = child.sendToSubcontractor();
        orders.save(child);
        orders.recordTransition(child.id(), sent, now, user.userId());
        log.info("Production order {} sent to subcontractor {} (PO {})", child.orderNumber(),
                child.subcontract().subcontractorId(), po.poNumber());
        return reads.get(user, child.id());
    }

    /**
     * G9: a goods receipt of a child's SUBCONTRACT PO puts what it counted in front of QC, once per receipt (a
     * redelivered event, a second part of the same PO). Receipts of other purchase orders are not this module's.
     */
    public void onReceipt(GoodsReceiptConfirmed receipt) {
        if (receipt.purchaseOrderId() == null) {
            return;
        }
        Objects.requireNonNull(receipt.receiptId(), "receiptId");
        orders.idOfPurchaseOrder(receipt.purchaseOrderId()).ifPresent(childId -> {
            ProductionOrder child = orders.findByIdForUpdate(childId).orElseThrow();
            int quantity = receipt.lines().stream()
                    .filter(line -> line.sku().equals(child.work().blankSku().code()))
                    .mapToInt(GoodsReceiptConfirmed.Line::quantity).sum();
            Instant now = now();
            if (quantity < 1 || !records.recordSubcontractReceipt(receipt.receiptId(), child.id(), quantity, now)) {
                return;
            }
            ProductionOrder.Transition received = child.receivedFromSubcontractor(quantity);
            orders.save(child);
            orders.recordTransition(child.id(), received, now, null);
            log.info("Production order {}: {} received from the subcontractor on {}", child.orderNumber(), quantity,
                    receipt.receiptNumber());
        });
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
