package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.inventory.api.InventoryControlService;
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
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
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
import java.util.stream.Collectors;

@Service
@Transactional
class TransferOrderServiceImpl implements TransferOrders {

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

    TransferOrderServiceImpl(
            TransferOrderRepository transfers,
            TransferOrderSearch search,
            WarehouseDirectory warehouses,
            StockItemRepository stockItems,
            StockMovementLog ledger,
            Clock clock,
            InventoryControlService controls,
            InventoryPolicyRepository policies,
            @Value("${stockflow.inventory.transfer.approval-threshold-units:100}")
                    int approvalThreshold) {
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
    @Auditable(
            action = AuditAction.CREATE,
            resourceType = "transfer-order",
            resourceId = "#result?.transferId()")
    public Transfer create(Create command) {
        String sourcePrefix = prefixOf(command.fromWarehouseId());
        prefixOf(command.toWarehouseId());
        Instant now = clock.instant();
        List<TransferLine> lines = new ArrayList<>();
        int lineNo = 1;
        for (NewLine line : command.lines()) {
            lines.add(
                    new TransferLine(
                            Identifiers.newId(),
                            lineNo++,
                            new Sku(line.sku()),
                            line.lotNumber(),
                            line.quantity(),
                            0));
        }
        TransferOrder order =
                TransferOrder.draft(
                        Identifiers.newId(),
                        "TO-pending",
                        command.fromWarehouseId(),
                        command.toWarehouseId(),
                        command.reason(),
                        command.expectedDate(),
                        null,
                        lines,
                        now);
        // SCRUM-330: every line must be coverable from sellable, unreserved stock at the source
        // now.
        // Checked again, under lock, at dispatch — this only stops a transfer nobody can fill.
        for (TransferLine line : order.lines()) {
            StockAllocator.plan(
                    line.sku().code(),
                    candidates(line, sourcePrefix),
                    Quantity.of(line.requested()),
                    controls.policy(line.sku()).removalStrategy(),
                    BusinessCalendar.date(now));
        }
        TransferOrder numbered =
                TransferOrder.draft(
                        order.id(),
                        transfers.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)),
                        order.fromWarehouseId(),
                        order.toWarehouseId(),
                        order.reason(),
                        order.expectedDate(),
                        null,
                        order.lines(),
                        now);
        return toView(transfers.save(numbered));
    }

    @Override
    @Transactional(readOnly = true)
    public Transfer get(UUID transferId) {
        return toView(load(transferId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<Row> list(
            List<TransferStatus> statuses,
            UUID fromWarehouseId,
            UUID toWarehouseId,
            String number,
            int page,
            int size) {
        var pageable = Pages.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return Pages.toResponse(
                search.search(
                                new TransferOrderSearch.Criteria(
                                        statuses, fromWarehouseId, toWarehouseId, number),
                                pageable)
                        .map(
                                r ->
                                        new Row(
                                                r.id(),
                                                r.number(),
                                                r.fromWarehouseId(),
                                                r.toWarehouseId(),
                                                r.status(),
                                                r.expectedDate(),
                                                r.createdAt(),
                                                r.dispatchedAt())));
    }

    @Override
    @Auditable(
            action = AuditAction.TRANSITION,
            resourceType = "transfer-order",
            resourceId = "#transferId")
    public Transfer submit(UUID transferId, UUID userId) {
        TransferOrder order = load(transferId);
        order.submit(userId, approvalThreshold, clock.instant());
        return toView(transfers.save(order));
    }

    @Override
    @Auditable(
            action = AuditAction.APPROVE,
            resourceType = "transfer-order",
            resourceId = "#transferId")
    public Transfer approve(UUID transferId, UUID approverId) {
        TransferOrder order = load(transferId);
        order.approve(approverId, clock.instant());
        return toView(transfers.save(order));
    }

    @Override
    @Auditable(
            action = AuditAction.REJECT,
            resourceType = "transfer-order",
            resourceId = "#transferId")
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
    @Auditable(
            action = AuditAction.TRANSITION,
            resourceType = "transfer-order",
            resourceId = "#transferId")
    public Transfer startPicking(UUID transferId) {
        TransferOrder order = load(transferId);
        order.startPicking();
        return toView(transfers.save(order));
    }

    @Override
    @Auditable(
            action = AuditAction.TRANSITION,
            resourceType = "transfer-order",
            resourceId = "#transferId")
    public Transfer cancelPicking(UUID transferId) {
        TransferOrder order = load(transferId);
        order.cancelPicking();
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
    @Auditable(
            action = AuditAction.TRANSITION,
            resourceType = "transfer-order",
            resourceId = "#transferId")
    public Transfer dispatch(UUID transferId, UUID userId, Map<UUID, Integer> shippedByLine) {
        TransferOrder order = load(transferId);
        Instant now = clock.instant();
        order.dispatch(userId, shippedByLine, now);
        String sourcePrefix = prefixOf(order.fromWarehouseId());
        // Match reservation and policy-edit lock order before planning or taking row locks.
        policies.lockReservationStock(
                order.lines().stream().map(line -> line.sku().code()).collect(Collectors.toSet()));

        record Take(TransferLine line, StockItemId stockItemId, Quantity quantity) {}
        List<Take> takes = new ArrayList<>();
        for (TransferLine line : order.lines()) {
            if (line.shipped() == 0) {
                continue;
            }
            for (StockAllocator.AllocationLine allocation :
                    StockAllocator.plan(
                            line.sku().code(),
                            candidates(line, sourcePrefix),
                            Quantity.of(line.shipped()),
                            controls.policy(line.sku()).removalStrategy(),
                            BusinessCalendar.date(now))) {
                takes.add(new Take(line, allocation.stockItemId(), allocation.quantity()));
            }
        }
        Map<StockItemId, StockItem> locked = new LinkedHashMap<>();
        takes.stream()
                .map(Take::stockItemId)
                .distinct()
                .sorted(Comparator.comparing(id -> id.value().toString()))
                .forEach(
                        id ->
                                locked.put(
                                        id,
                                        stockItems
                                                .findByIdForUpdate(id)
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalStateException(
                                                                        "Stock item %s vanished mid-dispatch"
                                                                                .formatted(id)))));
        for (Take take : takes) {
            StockItem item = locked.get(take.stockItemId());
            if (!item.status().isReservable()) {
                throw new InsufficientStockException(item.sku().code(), take.quantity().value(), 0);
            }
            item.moveOut(take.quantity());
            ledger.append(
                    new StockMovement(
                            Identifiers.newId(),
                            StockMovement.MovementType.TRANSFER_OUT,
                            item.sku(),
                            item.lotNumber(),
                            item.location(),
                            null,
                            take.quantity().value(),
                            StockStatus.AVAILABLE,
                            StockMovement.ReferenceType.TRANSFER_ORDER_LINE,
                            take.line().id(),
                            "Transfer " + order.number(),
                            userId,
                            now));
        }
        locked.values().forEach(stockItems::save);
        TransferOrder saved = transfers.save(order);
        log.info(
                "Dispatched transfer {}: {} unit(s) from {} stock row(s)",
                order.number(),
                takes.stream().mapToInt(t -> t.quantity().value()).sum(),
                locked.size());
        return toView(saved);
    }

    @Override
    @Auditable(
            action = AuditAction.TRANSITION,
            resourceType = "transfer-order",
            resourceId = "#transferId")
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
        return warehouses
                .prefixOf(Objects.requireNonNull(warehouseId, "warehouseId"))
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.WAREHOUSE_NOT_FOUND,
                                        "No warehouse " + warehouseId));
    }

    private TransferOrder load(UUID id) {
        return transfers
                .findById(id)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.TRANSFER_ORDER_NOT_FOUND,
                                        "No transfer order " + id));
    }

    private static Transfer toView(TransferOrder o) {
        return new Transfer(
                o.id(),
                o.number(),
                o.fromWarehouseId(),
                o.toWarehouseId(),
                o.status(),
                o.reason(),
                o.expectedDate(),
                o.lines().stream()
                        .map(
                                l ->
                                        new Line(
                                                l.id(),
                                                l.lineNo(),
                                                l.sku().code(),
                                                l.lotNumber(),
                                                l.requested(),
                                                l.shipped()))
                        .toList(),
                o.submittedBy(),
                o.submittedAt(),
                o.approvedBy(),
                o.approvedAt(),
                o.dispatchedBy(),
                o.dispatchedAt(),
                o.cancelReason(),
                o.createdAt(),
                o.version());
    }
}
