package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.inventory.internal.domain.InsufficientStockException;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.StockAllocator;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.domain.TransferLine;
import com.stockflow.inventory.internal.domain.TransferOrder;
import com.stockflow.inventory.internal.domain.TransferOrderRepository;
import com.stockflow.inventory.internal.domain.TransferStatus;
import com.stockflow.inventory.internal.domain.WarehouseDirectory;
import com.stockflow.inventory.internal.repository.TransferOrderSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.api.TransferDeliveryMode;
import com.stockflow.inventory.api.TransferDispatchGuard;
import com.stockflow.inventory.api.TransferFulfillment;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.repository.UomConversionRepository;
import org.springframework.beans.factory.ObjectProvider;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * See {@link TransferOrders}. Also {@link TransferFulfillment} (SCRUM-287 PR T): what fulfillment packs, load-plans and
 * dispatches a full-truckload transfer through; picking cancelled and a manual dispatch first ask fulfillment
 * ({@link TransferDispatchGuard}) whether the transfer is in its hands.
 */
@Service
@Transactional
class TransferOrderServiceImpl implements TransferOrders, TransferFulfillment {

    private static final Logger log = LoggerFactory.getLogger(TransferOrderServiceImpl.class);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final TransferOrderRepository transfers;
    private final TransferOrderSearch search;
    private final WarehouseDirectory warehouses;
    private final StockItemRepository stockItems;
    private final StockMovementLog ledger;
    private final Clock clock;
    private final int approvalThreshold;
    private final InventoryControlService controls;
    private final InventoryPolicyRepository policies;
    private final StockPackageContents cartons;
    private final StockPackageRepository stockPackages;
    private final UomConversionRepository units;
    /** Absent when fulfillment is not loaded (an inventory-only module test): nothing is packed then. */
    private final ObjectProvider<TransferDispatchGuard> guards;

    @SuppressWarnings("java:S107")
    TransferOrderServiceImpl(TransferOrderRepository transfers, TransferOrderSearch search,
                             WarehouseDirectory warehouses, StockItemRepository stockItems, StockMovementLog ledger,
                             Clock clock,
            InventoryControlService controls,
            InventoryPolicyRepository policies,
                             StockPackageContents cartons, StockPackageRepository stockPackages,
                             UomConversionRepository units, ObjectProvider<TransferDispatchGuard> guards,
                             @Value("${stockflow.inventory.transfer.approval-threshold-units:100}") int approvalThreshold) {
        this.cartons = cartons;
        this.stockPackages = stockPackages;
        this.units = units;
        this.guards = guards;
        this.transfers = transfers;
        this.search = search;
        this.warehouses = warehouses;
        this.stockItems = stockItems;
        this.ledger = ledger;
        this.clock = clock;
        this.approvalThreshold = approvalThreshold;
        this.controls = controls;
        this.policies = policies;
    }

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "transfer-order", resourceId = "#result?.transferId()")
    public Transfer create(Create command) {
        // BR-SEC-002: a warehouse-bound caller raises transfers into or out of their own warehouses.
        com.stockflow.common.security.WarehouseScope.requireEither(command.fromWarehouseId(), command.toWarehouseId());
        String sourcePrefix = activePrefixOf(command.fromWarehouseId());
        activePrefixOf(command.toWarehouseId());
        Instant now = clock.instant();
        TransferOrder order = TransferOrder.draft(Identifiers.newId(), "TO-pending", command.fromWarehouseId(),
                command.toWarehouseId(), command.reason(), command.expectedDate(), null, newLines(command.lines()), now);
        requireCoverable(order, sourcePrefix, now);
        TransferOrder numbered = TransferOrder.draft(order.id(),
                transfers.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)), order.fromWarehouseId(),
                order.toWarehouseId(), order.reason(), order.expectedDate(), null, order.lines(), now);
        numbered.travelAs(command.deliveryMode());
        return toView(transfers.save(numbered));
    }

    @Override
    @Transactional(readOnly = true)
    public Transfer get(UUID transferId) {
        return toView(load(transferId));
    }

    /**
     * kltn-docs 10 §5: a rejected transfer is back in DRAFT "for the planner to change". The caller
     * must be allowed the transfer as it was and as it will be (BR-SEC-002), both warehouses must be
     * ACTIVE, and every line coverable at the (new) source now, as at creation.
     */
    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "transfer-order", resourceId = "#transferId")
    public Transfer reviseDraft(UUID transferId, Revise command) {
        TransferOrder order = load(transferId);
        if (order.status() != TransferStatus.DRAFT) {
            throw new BusinessException(ErrorCode.TRANSFER_ORDER_NOT_EDITABLE,
                    "Transfer %s is %s".formatted(order.number(), order.status()));
        }
        if (command.version() != order.version()) {
            throw new BusinessException(ErrorCode.OPTIMISTIC_LOCK,
                    "Transfer " + order.number() + " changed since it was read; reload it");
        }
        com.stockflow.common.security.WarehouseScope.requireEither(command.fromWarehouseId(), command.toWarehouseId());
        String sourcePrefix = activePrefixOf(command.fromWarehouseId());
        activePrefixOf(command.toWarehouseId());
        order.reviseDraft(command.fromWarehouseId(), command.toWarehouseId(), command.reason(),
                command.expectedDate(), newLines(command.lines()));
        order.travelAs(command.deliveryMode());
        requireCoverable(order, sourcePrefix, clock.instant());
        return toView(transfers.save(order));
    }

    private static List<TransferLine> newLines(List<NewLine> requested) {
        List<TransferLine> lines = new ArrayList<>();
        int lineNo = 1;
        for (NewLine line : requested) {
            lines.add(new TransferLine(Identifiers.newId(), lineNo++, new Sku(line.sku()), line.lotNumber(),
                    line.quantity(), 0));
        }
        return lines;
    }

    /**
     * SCRUM-330: every line must be coverable from sellable, unreserved stock at the source now.
     * Checked again, under lock, at dispatch — this only stops a transfer nobody can fill.
     */
    private void requireCoverable(TransferOrder order, String sourcePrefix, Instant now) {
        for (TransferLine line : order.lines()) {
            StockAllocator.plan(line.sku().code(),
                    candidates(line, sourcePrefix),
                    Quantity.of(line.requested()),
                    controls.policy(line.sku()).removalStrategy(),
                    BusinessCalendar.date(now));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<Row> list(List<TransferStatus> statuses, UUID fromWarehouseId, UUID toWarehouseId,
                                  String number, int page, int size) {
        var pageable = Pages.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return Pages.toResponse(search.search(new TransferOrderSearch.Criteria(statuses, fromWarehouseId,
                        toWarehouseId, number), pageable)
                .map(r -> new Row(r.id(), r.number(), r.fromWarehouseId(), r.toWarehouseId(), r.status(),
                        r.expectedDate(), r.createdAt(), r.dispatchedAt())));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "transfer-order", resourceId = "#transferId")
    public Transfer submit(UUID transferId, UUID userId) {
        TransferOrder order = load(transferId);
        order.submit(userId, approvalThreshold, clock.instant());
        return toView(transfers.save(order));
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "transfer-order", resourceId = "#transferId")
    public Transfer approve(UUID transferId, UUID approverId) {
        TransferOrder order = load(transferId);
        order.approve(approverId, clock.instant());
        return toView(transfers.save(order));
    }

    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "transfer-order", resourceId = "#transferId")
    public Transfer reject(UUID transferId, UUID approverId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A rejection must say why");
        }
        TransferOrder order = load(transferId);
        order.reject(approverId);
        log.info("Transfer {} rejected: {}", order.number(), reason);
        return toView(transfers.save(order));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "transfer-order", resourceId = "#transferId")
    public Transfer startPicking(UUID transferId) {
        TransferOrder order = loadAtSource(transferId);
        order.startPicking();
        return toView(transfers.save(order));
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "transfer-order", resourceId = "#transferId")
    public Transfer cancelPicking(UUID transferId) {
        TransferOrder order = loadAtSource(transferId);
        // 10 BR-04: refused once goods left on a vehicle; then fulfillment voids its parcels and calls its plan off
        // (refused while a vehicle of the plan is being loaded, 09 BR-14).
        order.cancelPicking();
        TransferDispatchGuard guard = guards.getIfAvailable();
        if (guard != null) {
            guard.beforePickingCancelled(order.id(), clock.instant());
        }
        return toView(transfers.save(order));
    }

    /**
     * The goods leave the source (SCRUM-327 / 332): per line, the configured FIFO/FEFO policy over
     * unexpired, sellable, unreserved stock in the source warehouse (and the line's lot, when it
     * names one); every stock row is locked in id order across all lines, as reserve() does, then
     * lowered, with one TRANSFER_OUT ledger line each. The units are then owned by the transfer —
     * counted in no warehouse — until receipt.
     */
    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "transfer-order", resourceId = "#transferId")
    public Transfer dispatch(UUID transferId, UUID userId, Map<UUID, Integer> shippedByLine) {
        TransferOrder order = loadAtSource(transferId);
        TransferDispatchGuard guard = guards.getIfAvailable();
        if (guard != null) {
            guard.requireManualDispatch(order.id());
        }
        Instant now = clock.instant();
        order.dispatch(userId, shippedByLine, now);
        return toView(takeFromSource(order, userId, now, Map.of()));
    }

    /**
     * The dispatched lines' units leave the source: an unopened carton shipped as it is ({@code cartonsByLine}, LP2-8)
     * gives its own rows first; the rest is planned by the line's removal policy over the other sellable rows.
     */
    private TransferOrder takeFromSource(TransferOrder order, UUID userId, Instant now,
                                         Map<UUID, List<UUID>> cartonsByLine) {
        String sourcePrefix = prefixOf(order.fromWarehouseId());
        // Match reservation and policy-edit lock order before planning or taking row locks.
        policies.lockReservationStock(
                order.lines().stream().map(line -> line.sku().code()).collect(Collectors.toSet()));

        record Take(TransferLine line, StockItemId stockItemId, Quantity quantity) {
        }
        List<Take> takes = new ArrayList<>();
        for (TransferLine line : order.lines()) {
            if (line.shipped() == 0) {
                continue;
            }
            int left = line.shipped();
            Set<StockItemId> fromCartons = new HashSet<>();
            for (UUID carton : cartonsByLine.getOrDefault(line.id(), List.of())) {
                for (StockItemId rowId : stockItems.findIdsInStockPackage(new StockPackageId(carton))) {
                    StockItem row = stockItems.findById(rowId).orElse(null);
                    if (left == 0 || row == null || !row.sku().equals(line.sku())
                            || !row.location().warehouseCode().equals(sourcePrefix) || !row.status().isReservable()
                            || row.available().isZero()) {
                        continue;
                    }
                    int quantity = Math.min(left, row.available().value());
                    takes.add(new Take(line, rowId, Quantity.of(quantity)));
                    fromCartons.add(rowId);
                    left -= quantity;
                }
            }
            if (left == 0) {
                continue;
            }
            for (StockAllocator.AllocationLine allocation : StockAllocator.plan(line.sku().code(),
                    candidates(line, sourcePrefix).stream().filter(c -> !fromCartons.contains(c.stockItemId()))
                            .toList(), Quantity.of(left),
                            controls.policy(line.sku()).removalStrategy(),
                            BusinessCalendar.date(now))) {
                takes.add(new Take(line, allocation.stockItemId(), allocation.quantity()));
            }
        }
        Map<StockItemId, StockItem> locked = new LinkedHashMap<>();
        takes.stream().map(Take::stockItemId).distinct()
                .sorted(Comparator.comparing(id -> id.value().toString()))
                .forEach(id -> locked.put(id, stockItems.findByIdForUpdate(id).orElseThrow(() ->
                        new IllegalStateException("Stock item %s vanished mid-dispatch".formatted(id)))));
        // Already locked by lockReservationStock, ahead of the rows; read here so taking from one opens or empties it.
        // Transfers do not move cartons yet (V20261020000100 "Not yet"): the units leave their carton and travel loose.
        StockPackageContents.LockedCartons cartonsTaken = cartons.lockFor(locked.values());
        for (Take take : takes) {
            StockItem item = locked.get(take.stockItemId());
            if (!item.status().isReservable()) {
                throw new InsufficientStockException(item.sku().code(), take.quantity().value(), 0);
            }
            cartonsTaken.tookFrom(item);
            item.moveOut(take.quantity());
            ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.TRANSFER_OUT,
                    item.sku(), item.lotNumber(), item.location(), null, take.quantity().value(), StockStatus.AVAILABLE,
                    StockMovement.ReferenceType.TRANSFER_ORDER_LINE, take.line().id(),
                    "Transfer " + order.number(), userId, now).leaving(item.stockPackageId()));
        }
        locked.values().forEach(stockItems::save);
        cartons.settle(cartonsTaken, now);
        TransferOrder saved = transfers.save(order);
        log.info("Dispatched transfer {}: {} unit(s) from {} stock row(s)", order.number(),
                takes.stream().mapToInt(t -> t.quantity().value()).sum(), locked.size());
        return saved;
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "transfer-order", resourceId = "#transferId")
    public Transfer cancel(UUID transferId, String reason) {
        TransferOrder order = load(transferId);
        order.cancel(reason);
        return toView(transfers.save(order));
    }

    private List<StockAllocator.Candidate> candidates(TransferLine line, String warehousePrefix) {
        return stockItems.findAvailabilityBySku(line.sku()).stream()
                .filter(c -> c.location().warehouseCode().equals(warehousePrefix))
                .filter(c -> line.lotNumber() == null || line.lotNumber().equals(c.lotNumber()))
                .filter(c -> c.status().isReservable())
                .toList();
    }

    private String prefixOf(UUID warehouseId) {
        return warehouses.prefixOf(Objects.requireNonNull(warehouseId, "warehouseId")).orElseThrow(() ->
                new BusinessException(ErrorCode.WAREHOUSE_NOT_FOUND, "No warehouse " + warehouseId));
    }

    /** A transfer is raised only between ACTIVE warehouses (SCRUM-462): nothing ships from or to a closed one. */
    private String activePrefixOf(UUID warehouseId) {
        String prefix = prefixOf(warehouseId);
        if (!warehouses.isActive(warehouseId)) {
            throw new BusinessException(ErrorCode.WAREHOUSE_INACTIVE, "Warehouse " + prefix + " is not active");
        }
        return prefix;
    }

    /** The order, if the caller is assigned to one of its two warehouses (BR-SEC-002). */
    private TransferOrder load(UUID id) {
        TransferOrder order = transfers.findById(id).orElseThrow(() ->
                new BusinessException(ErrorCode.TRANSFER_ORDER_NOT_FOUND, "No transfer order " + id));
        com.stockflow.common.security.WarehouseScope.requireEither(order.fromWarehouseId(), order.toWarehouseId());
        return order;
    }

    /** Picking and dispatch happen on the source warehouse's floor, so only its staff do them. */
    private TransferOrder loadAtSource(UUID id) {
        TransferOrder order = load(id);
        com.stockflow.common.security.WarehouseScope.requireWarehouse(order.fromWarehouseId());
        return order;
    }

    private static Transfer toView(TransferOrder o) {
        return new Transfer(o.id(), o.number(), o.fromWarehouseId(), o.toWarehouseId(), o.status(), o.reason(),
                o.expectedDate(), o.lines().stream().map(l -> new TransferOrders.Line(l.id(), l.lineNo(),
                        l.sku().code(), l.lotNumber(), l.requested(), l.shipped())).toList(),
                o.submittedBy(), o.submittedAt(), o.approvedBy(), o.approvedAt(), o.dispatchedBy(), o.dispatchedAt(),
                o.cancelReason(), o.createdAt(), o.version(), o.deliveryMode(), o.packedAt(), o.goodsLeftAt());
    }

    // ------------------------------------------------------------------ TransferFulfillment (SCRUM-287 PR T)

    @Override
    @Transactional(readOnly = true)
    public Optional<TransferView> find(UUID transferId) {
        return transfers.findById(transferId).map(TransferOrderServiceImpl::fulfillmentView);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TransferView> findAll(Collection<UUID> transferIds) {
        return transferIds.stream().distinct().map(transfers::findById).flatMap(Optional::stream)
                .map(TransferOrderServiceImpl::fulfillmentView).toList();
    }

    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "transfer-order", resourceId = "#transferId")
    public TransferView beginPacking(UUID transferId) {
        TransferOrder order = loadAtSource(transferId);
        if (!order.beginPacking()) {
            return fulfillmentView(order);
        }
        log.info("Transfer {}: picking started for packing (load planning)", order.number());
        return fulfillmentView(transfers.save(order));
    }

    @Override
    public TransferView markPacked(UUID transferId, Instant at) {
        TransferOrder order = loadAtSource(transferId);
        order.markPacked(at);
        return fulfillmentView(transfers.save(order));
    }

    @Override
    public TransferView recordGoodsLeft(UUID transferId, Instant at) {
        TransferOrder order = loadAtSource(transferId);
        if (!order.recordGoodsLeft(at)) {
            return fulfillmentView(order);
        }
        log.info("Transfer {}: goods left on a vehicle; picking is no longer cancelled (10 BR-04)", order.number());
        return fulfillmentView(transfers.save(order));
    }

    /**
     * 10 step 5: the transfer leaves with what its load plan carried. Packed first ({@code TRANSFER_NOT_PACKED}); the
     * line quantities are the packed ones, so nothing here is a choice - the dispatch is the packing's.
     */
    @Override
    @Auditable(action = AuditAction.TRANSITION, resourceType = "transfer-order", resourceId = "#transferId")
    public TransferView dispatchLoaded(UUID transferId, UUID userId, Map<UUID, Integer> shippedByLine,
                                       Map<UUID, List<UUID>> cartonsByLine) {
        TransferOrder order = loadAtSource(transferId);
        if (order.status() == TransferStatus.PICKING && order.packedAt() == null) {
            throw new BusinessException(ErrorCode.TRANSFER_NOT_PACKED,
                    "Transfer %s is not packed for dispatch".formatted(order.number()));
        }
        Instant now = clock.instant();
        order.dispatch(userId, shippedByLine, now);
        return fulfillmentView(takeFromSource(order, userId, now, cartonsByLine == null ? Map.of() : cartonsByLine));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Carton> carton(String lpn, UUID warehouseId) {
        if (lpn == null || lpn.isBlank()) {
            return Optional.empty();
        }
        Optional<StockPackage> found = stockPackages.findByLpn(lpn.trim());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        StockPackage carton = found.get();
        String sku = policies.findSku(carton.inventoryItemId()).orElse(null);
        List<StockItem> rows = stockItems.findIdsInStockPackage(carton.id()).stream().map(stockItems::findById)
                .flatMap(Optional::stream).toList();
        int quantity = rows.stream().mapToInt(r -> r.onHand().value()).sum();
        boolean sellable = !rows.isEmpty() && rows.stream().allMatch(r -> r.status().isReservable()
                && r.reserved().isZero());
        String lot = rows.stream().map(StockItem::lotNumber).filter(Objects::nonNull).findFirst().orElse(null);
        String prefix = warehouseId == null ? null : warehouses.prefixOf(warehouseId).orElse(null);
        String unit = sku == null ? null : units.find(sku, carton.uomConversionId())
                .map(com.stockflow.inventory.api.UomConversion::unitOfMeasure).orElse(null);
        return Optional.of(new Carton(carton.id().value(), carton.lpn(), sku, lot, quantity,
                prefix != null && carton.location().warehouseCode().equals(prefix), carton.location().code(),
                carton.uomConversionId(), unit, carton.unopened(), carton.status() == StockPackageStatus.IN_STOCK,
                sellable));
    }

    private static TransferView fulfillmentView(TransferOrder o) {
        return new TransferView(o.id(), o.number(), o.fromWarehouseId(), o.toWarehouseId(), o.status().name(),
                o.deliveryMode() == null ? TransferDeliveryMode.FULL_TRUCKLOAD : o.deliveryMode(), o.packedAt(),
                o.goodsLeftAt(), o.dispatchedAt(), o.lines().stream().map(l -> new TransferFulfillment.Line(l.id(),
                        l.lineNo(), l.sku().code(), l.lotNumber(), l.requested(), l.shipped())).toList());
    }
}
