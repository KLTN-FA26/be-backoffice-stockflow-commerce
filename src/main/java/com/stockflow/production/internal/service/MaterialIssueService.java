package com.stockflow.production.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.error.NotFoundException;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.ScopeGuards;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.inventory.api.HoldView;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.IssueReservedStockCommand;
import com.stockflow.inventory.api.IssueReservedStockResult;
import com.stockflow.inventory.api.ReserveProductionStockCommand;
import com.stockflow.inventory.api.StockReservation;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.production.internal.domain.ProductionOrder;
import com.stockflow.production.internal.domain.ProductionOrderRepository;
import com.stockflow.production.internal.domain.ProductionRecords;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * One scan, one transaction: the order row is locked first, so two scans of one order run one after the
 * other and the second sees what the first issued; the stock rows are locked by inventory in id order. The
 * hold moves, the order counts the blanks, the issue row is written — all or nothing. A count beyond what the
 * order still needs (BR-PRD-03) is refused after the move and rolls the move back with it.
 *
 * <p>Idempotent on the scan's request id, checked before and again under the order lock: a retry that waited
 * for its own first attempt finds the row and moves nothing.</p>
 */
@Service
@Transactional
class MaterialIssueService implements MaterialIssues {

    private static final Logger log = LoggerFactory.getLogger(MaterialIssueService.class);

    private final ProductionOrderRepository orders;
    private final ProductionRecords records;
    private final ProductionOrders reads;
    private final InventoryService inventory;
    private final OrderService salesOrders;
    private final WarehouseService warehouses;
    private final InventoryControlService itemControls;
    private final Clock clock;

    MaterialIssueService(ProductionOrderRepository orders, ProductionRecords records, ProductionOrders reads,
                         InventoryService inventory, OrderService salesOrders, WarehouseService warehouses,
                         InventoryControlService itemControls, Clock clock) {
        this.itemControls = itemControls;
        this.orders = orders;
        this.records = records;
        this.reads = reads;
        this.inventory = inventory;
        this.salesOrders = salesOrders;
        this.warehouses = warehouses;
        this.clock = clock;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "production-material-issue", resourceId = "#productionOrderId")
    public Result issue(CurrentUser user, UUID productionOrderId, Issue issue) {
        ScopeGuards.requireBackOffice(user);
        Objects.requireNonNull(productionOrderId, "productionOrderId");
        if (issue == null || issue.requestId() == null || issue.toLocation() == null || issue.toLocation().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A scan names its request id and the PRODUCTION area");
        }
        Optional<Result> replay = replay(user, productionOrderId, issue.requestId());
        if (replay.isPresent()) {
            return replay.get();
        }
        ProductionOrder order = orders.findByIdForUpdate(productionOrderId)
                .orElseThrow(() -> new NotFoundException("production-order", productionOrderId));
        WarehouseScope.requireWarehouse(order.work().warehouseId());
        replay = replay(user, productionOrderId, issue.requestId());
        if (replay.isPresent()) {
            return replay.get();
        }
        if (order.status() != ProductionOrder.Status.READY) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Production order %s is %s; blanks are issued while READY".formatted(order.orderNumber(),
                            order.status()));
        }
        if (order.mode() == ProductionOrder.Mode.SUBCONTRACTED) {
            // A converted order stays READY until it is sent out; its blanks leave with the dispatch, not to the press.
            throw new BusinessException(ErrorCode.INVALID_PRODUCTION_TRANSITION,
                    "Production order %s is printed by a subcontractor; its blanks are sent out, not issued"
                            .formatted(order.orderNumber()));
        }
        StorageLocationView to = productionArea(order, issue.toLocation());

        UUID chosen = choose(order, issue);
        requireScanned(order, chosen, issue);
        IssueReservedStockResult moved = inventory.issueReserved(new IssueReservedStockCommand(chosen,
                order.salesOrderId(), order.id(), to.locationCode(), user.userId()));
        if (!moved.moved()) {
            // Only this service moves a hold into the print shop, and it writes the row below in the same
            // transaction; a hold already there without a row means something else moved it.
            throw new BusinessException(ErrorCode.RESERVATION_NOT_ISSUABLE,
                    "Hold %s is already at %s".formatted(moved.reservationId(), moved.toLocation()));
        }
        // Microseconds, as the column keeps them: a replay must read back exactly what this call answered.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Optional<ProductionOrder.Transition> issued = order.issueBlanks(moved.quantity());
        orders.save(order);
        issued.ifPresent(transition -> orders.recordTransition(order.id(), transition, now, user.userId()));
        ProductionRecords.MaterialIssue row = new ProductionRecords.MaterialIssue(Identifiers.newId(), order.id(),
                issue.requestId(), moved.reservationId(), moved.sku(), moved.lotNumber(), moved.fromLocation(),
                moved.toLocation(), moved.quantity(), moved.movementId(), now, user.userId());
        records.recordIssue(row);
        log.info("Issued {} blank(s) of {} to {} for production order {} ({} still needed)", moved.quantity(),
                moved.sku(), moved.toLocation(), order.orderNumber(), order.outstandingBlanks());
        return new Result(view(row), reads.get(user, order.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Issued> list(CurrentUser user, UUID productionOrderId) {
        reads.get(user, productionOrderId);   // 404 and warehouse scope, as for the order itself
        return records.issuesOf(productionOrderId).stream().map(MaterialIssueService::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Pending pending(CurrentUser user, UUID productionOrderId) {
        reads.get(user, productionOrderId);   // 404 and warehouse scope, as for the order itself
        ProductionOrder order = orders.findById(productionOrderId)
                .orElseThrow(() -> new NotFoundException("production-order", productionOrderId));
        List<PendingHold> holds = order.status() != ProductionOrder.Status.READY
                || order.mode() == ProductionOrder.Mode.SUBCONTRACTED ? List.of()
                : waitingViews(order).stream()
                        .map(hold -> new PendingHold(hold.reservationId(), hold.sku(), hold.lotNumber(),
                                hold.locationCode(), hold.quantity(), barcodeOf(hold.sku())))
                        .toList();
        int outstanding = order.outstandingBlanks();
        return new Pending(order.id(), order.orderNumber(), order.status().name(), order.work().blankSku().code(),
                order.issued() + outstanding, order.issued(), outstanding, holds);
    }

    /**
     * SCRUM-409 "scan confirmation": what the clerk scanned at the source must be the hold being issued - its
     * location, then its SKU or the SKU's barcode - checked before anything moves. A scan that names neither keeps
     * the SCRUM-397 contract, where the hold alone decides.
     */
    private void requireScanned(ProductionOrder order, UUID chosen, Issue issue) {
        boolean location = issue.scannedLocation() != null && !issue.scannedLocation().isBlank();
        boolean code = issue.scannedCode() != null && !issue.scannedCode().isBlank();
        if (!location && !code) {
            return;
        }
        HoldView hold = inventory.holds(List.of(chosen)).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_ISSUABLE,
                        "Hold %s of production order %s no longer exists".formatted(chosen, order.orderNumber())));
        if (location && !sameCode(issue.scannedLocation(), hold.locationCode())) {
            throw new BusinessException(ErrorCode.ISSUE_LOCATION_MISMATCH,
                    "This hold's blanks are at " + hold.locationCode());
        }
        if (code && !sameCode(issue.scannedCode(), hold.sku()) && !issue.scannedCode().trim().equals(barcodeOf(hold.sku()))) {
            throw new BusinessException(ErrorCode.ISSUE_ITEM_MISMATCH, "This hold is " + hold.sku());
        }
    }

    /** Location codes and SKUs are stored upper case; a scanner or a keyboard may send either. */
    private static boolean sameCode(String scanned, String expected) {
        return expected != null && scanned.trim().toUpperCase(Locale.ROOT).equals(expected.toUpperCase(Locale.ROOT));
    }

    private String barcodeOf(String sku) {
        return itemControls.logistics(new Sku(sku)).logistics().barcode();
    }

    private Optional<Result> replay(CurrentUser user, UUID productionOrderId, UUID requestId) {
        return records.findIssue(requestId).map(done -> {
            if (!done.productionOrderId().equals(productionOrderId)) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "Request %s already issued blanks to another production order".formatted(requestId));
            }
            return new Result(view(done), reads.get(user, productionOrderId));
        });
    }

    private StorageLocationView productionArea(ProductionOrder order, String code) {
        StorageLocationView to = warehouses.findLocation(code)
                .filter(location -> location.warehouseId().equals(order.work().warehouseId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.LOCATION_NOT_FOUND,
                        "No location %s in the warehouse of production order %s".formatted(code, order.orderNumber())));
        if (to.areaType() != StorageLocationView.AreaType.PRODUCTION) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "%s is not a PRODUCTION area; blanks are issued to the print shop".formatted(to.locationCode()));
        }
        if (!to.usable()) {
            throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now".formatted(to.locationCode()));
        }
        return to;
    }

    /**
     * The hold scanned, or else the next one not issued yet: the sales order's own holds first (a root ORDER's
     * blanks were held at checkout), then the production order's (a sample's, a reprint's). When none is left
     * and blanks are still needed — a reprint round, or a round resumed after BLANKS_SHORT — they are held now
     * for the production order, in its warehouse; too few in stock refuses the scan and holds nothing.
     */
    private UUID choose(ProductionOrder order, Issue issue) {
        List<UUID> candidates = waiting(order);
        if (issue.reservationId() != null) {
            return candidates.stream().filter(issue.reservationId()::equals).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESERVATION_NOT_ISSUABLE,
                            "Hold %s is not a hold of production order %s waiting to be issued"
                                    .formatted(issue.reservationId(), order.orderNumber())));
        }
        if (!candidates.isEmpty()) {
            return candidates.get(0);
        }
        int needed = order.outstandingBlanks();
        if (needed == 0) {
            throw new BusinessException(ErrorCode.ISSUE_EXCEEDS_REQUIREMENT,
                    "Production order %s needs no more blanks (BR-PRD-03)".formatted(order.orderNumber()));
        }
        String warehouse = warehouses.findWarehouse(order.work().warehouseId()).orElseThrow().prefix();
        List<StockReservation> held = inventory.reserveForProduction(new ReserveProductionStockCommand(
                UUID.nameUUIDFromBytes(("production-issue:" + issue.requestId()).getBytes(StandardCharsets.UTF_8)),
                order.work().blankSku(), needed, order.id(), warehouse)).reservations();
        log.info("Held {} more blank(s) of {} for production order {}", needed, order.work().blankSku(),
                order.orderNumber());
        return held.get(0).reservationId();
    }

    /**
     * What {@link #waiting} would choose from, read as it stands for the screen: nothing is locked, so a GET never
     * waits on (or holds up) a scan. The scan itself re-reads under its locks.
     */
    private List<HoldView> waitingViews(ProductionOrder order) {
        Set<UUID> issued = records.issuesOf(order.id()).stream()
                .map(ProductionRecords.MaterialIssue::reservationId).collect(Collectors.toSet());
        List<HoldView> views = new ArrayList<>(inventory.holds(salesOrderHolds(order)));
        views.addAll(inventory.productionHoldViews(order.id()));
        return views.stream().filter(hold -> hold.status() == HoldView.Status.HELD)
                .filter(hold -> !issued.contains(hold.reservationId())).toList();
    }

    /** A root ORDER's blanks were held at checkout, on its sales order line; any other order has none there. */
    private List<UUID> salesOrderHolds(ProductionOrder order) {
        if (order.type() != ProductionOrder.Type.ORDER || order.parentId() != null) {
            return List.of();
        }
        OrderSummary sales = salesOrders.findById(order.salesOrderId()).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND, "Sales order of production order %s not found".formatted(order.orderNumber())));
        return sales.lines().stream().filter(line -> line.lineId().equals(order.salesOrderLineId()))
                .flatMap(line -> line.reservationIds().stream()).toList();
    }

    /**
     * The holds of the order not issued yet: the sales order's own holds first (a root ORDER's blanks were held at
     * checkout), then the production order's (a sample's, a reprint's).
     */
    private List<UUID> waiting(ProductionOrder order) {
        Set<UUID> issued = records.issuesOf(order.id()).stream()
                .map(ProductionRecords.MaterialIssue::reservationId).collect(Collectors.toSet());
        // The line's checkout holds are its root order's: an in-house order made for a subcontractor's shortfall
        // works on the holds of its own.
        List<UUID> candidates = new ArrayList<>(salesOrderHolds(order));
        inventory.productionHolds(order.id()).stream().map(StockReservation::reservationId).forEach(candidates::add);
        candidates.removeIf(issued::contains);
        return candidates;
    }

    private static Issued view(ProductionRecords.MaterialIssue row) {
        return new Issued(row.id(), row.productionOrderId(), row.requestId(), row.reservationId(), row.sku(),
                row.lotNumber(), row.fromLocation(), row.toLocation(), row.quantity(), row.stockMovementId(),
                row.issuedAt(), row.issuedBy());
    }
}
