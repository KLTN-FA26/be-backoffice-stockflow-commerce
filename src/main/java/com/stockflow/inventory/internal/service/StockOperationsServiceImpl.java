package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.WarehouseScope;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.inventory.api.MoveStockCommand;
import com.stockflow.inventory.api.ReclassifyStockCommand;
import com.stockflow.inventory.api.ReceiveStockCommand;
import com.stockflow.inventory.api.RequestAdjustmentCommand;
import com.stockflow.inventory.api.StockAdjustmentReason;
import com.stockflow.inventory.api.StockAdjustmentStatus;
import com.stockflow.inventory.api.StockAdjustmentSummary;
import com.stockflow.inventory.api.StockMove;
import com.stockflow.inventory.internal.domain.AdjustmentReason;
import com.stockflow.inventory.internal.domain.AdjustmentStatus;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.StockAdjustment;
import com.stockflow.inventory.internal.domain.StockAdjustmentRepository;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.StockAdjustmentSearch;
import com.stockflow.inventory.internal.repository.StockLedgerSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Moves stock between locations and posts approved adjustments, writing the ledger as it goes.
 *
 * <p>Every change of {@code onHand} made here leaves exactly one {@code inventory.stock_movement}
 * line, in the same transaction — the history screen and the stock figure can never disagree.</p>
 */
@Service
@Transactional
class StockOperationsServiceImpl implements StockOperations {

    private static final Logger log = LoggerFactory.getLogger(StockOperationsServiceImpl.class);

    /** Document numbers follow the business's calendar, not UTC: ADJ-20261008 is the 8th in Saigon. */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private static final SortWhitelist ADJUSTMENT_SORT = SortWhitelist.of("createdAt", "status", "sku")
            .withDefault("createdAt", Sort.Direction.DESC);

    private final StockItemRepository stockItems;
    private final StockAdjustmentRepository adjustments;
    private final StockAdjustmentSearch adjustmentSearch;
    private final StockMovementLog ledger;
    private final StockLedgerSearch ledgerSearch;
    private final LocationDirectory locations;
    private final Clock clock;
    private final InventoryPolicyRepository policies;

    StockOperationsServiceImpl(StockItemRepository stockItems, StockAdjustmentRepository adjustments,
                               StockAdjustmentSearch adjustmentSearch, StockMovementLog ledger,
                               StockLedgerSearch ledgerSearch, LocationDirectory locations, Clock clock,
                               InventoryPolicyRepository policies) {
        this.stockItems = stockItems;
        this.adjustments = adjustments;
        this.adjustmentSearch = adjustmentSearch;
        this.ledger = ledger;
        this.ledgerSearch = ledgerSearch;
        this.locations = locations;
        this.clock = clock;
        this.policies = policies;
    }

    // ------------------------------------------------------------------ move

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-move", resourceId = "#command.requestId()")
    public StockMove move(MoveStockCommand command) {
        StockMovement.ReferenceType referenceType = StockMovement.ReferenceType.valueOf(command.reference().name());
        UUID referenceId = command.referenceId() != null ? command.referenceId() : command.requestId();
        return relocate(command.sku(), command.lotNumber(), command.receivedAt(), command.fromLocation(),
                command.toLocation(), command.quantity(), null, referenceType, referenceId, command.actorId(), null);
    }

    @Override
    @Auditable(action = AuditAction.UPDATE, resourceType = "stock-move", resourceId = "#command.referenceId()")
    public StockMove reclassify(ReclassifyStockCommand command) {
        return relocate(command.sku(), command.lotNumber(), command.receivedAt(), command.fromLocation(),
                command.toLocation(), command.quantity(), StockStatus.valueOf(command.disposition().name()),
                StockMovement.ReferenceType.valueOf(command.reference().name()), command.referenceId(),
                command.actorId(), blankToNull(command.reason()));
    }

    /**
     * The one implementation of moving stock between two locations, for a plain move ({@code target}
     * null: the status travels with the goods) and for a reclassification (the goods arrive as
     * {@code target}). {@code receivedAt} picks the stock layer when the source holds several.
     */
    private StockMove relocate(Sku sku, String lotNumber, Instant receivedAt, String fromCode, String toCode,
                               int qty, StockStatus target, StockMovement.ReferenceType referenceType,
                               UUID referenceId, UUID actorId, String reason) {
        // BR-SEC-002 (SCRUM-457): both ends in a warehouse the caller is assigned to. First, so a
        // replay cannot reveal a move made in someone else's warehouse.
        WarehouseScope.requireLocation(fromCode);
        WarehouseScope.requireLocation(toCode);
        // Match policy edits/reservations: take the SKU lock before any stock-row locks.
        policies.lock(sku.code());

        // Replay first: a retried move must not move the goods again.
        Optional<StockMovement> done = ledger.findByReference(StockMovement.MovementType.MOVE, referenceType, referenceId);
        if (done.isPresent()) {
            return toMove(done.get());
        }

        LocationId from = new LocationId(fromCode);
        LocationId to = new LocationId(toCode);
        if (from.equals(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A move needs two different locations");
        }
        // Docs 11 BR-01: stock leaves its warehouse only on a transfer order (docs 10), which records the
        // dispatch, the transit and the receipt. A plain move between warehouses skipped all of that.
        if (!from.warehouseCode().equals(to.warehouseCode())) {
            throw new BusinessException(ErrorCode.MOVE_ACROSS_WAREHOUSES,
                    "%s and %s are in different warehouses; use a transfer order".formatted(fromCode, toCode));
        }
        // Only the destination is checked (issue #67): taking stock out of a blocked bin or a shelf in
        // maintenance is how it gets emptied, and an adjustment at a bin blocked for counting must
        // still post.
        switch (locations.stateOf(to)) {
            case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + to);
            case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it, its shelf or its warehouse is not active".formatted(to));
            case USABLE -> { }
        }
        Quantity quantity = Quantity.of(qty);

        StockItem sourceView = findLayer(sku, from, lotNumber, receivedAt)
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_ITEM_NOT_FOUND,
                        "No %s%s at %s".formatted(sku, lotSuffix(lotNumber), from)));
        Optional<StockItem> destinationView = findLayer(sku, to, lotNumber, sourceView.receivedAt())
                .filter(item -> java.util.Objects.equals(item.receivedAt(), sourceView.receivedAt()));

        // Lock in id order, as reserve() does: two moves crossing the same pair in opposite
        // directions would otherwise deadlock.
        List<StockItem> toLock = destinationView.isPresent()
                ? List.of(sourceView, destinationView.get()) : List.of(sourceView);
        StockItem source = null;
        StockItem destination = null;
        for (StockItem view : toLock.stream()
                .sorted(Comparator.comparing(item -> item.id().value().toString())).toList()) {
            StockItem locked = stockItems.findByIdForUpdate(view.id()).orElseThrow(() ->
                    new IllegalStateException("Stock item %s vanished mid-move".formatted(view.id())));
            if (locked.id().equals(sourceView.id())) {
                source = locked;
            } else {
                destination = locked;
            }
        }
        Objects.requireNonNull(source, "source");

        if (target != null && source.status() != StockStatus.INBOUND && source.status() != StockStatus.QUARANTINE) {
            throw new BusinessException(ErrorCode.STOCK_STATUS_MISMATCH,
                    "%s%s at %s is %s; only INBOUND or QUARANTINE stock takes a receiving decision".formatted(
                            sku, lotSuffix(lotNumber), from, source.status()));
        }
        StockStatus arriving = target != null ? target : source.status();
        if (destination != null && !destination.canReceiveFrom(source, arriving)) {
            throw new BusinessException(
                    destination.status() == arriving
                            ? ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT
                            : ErrorCode.STOCK_STATUS_MISMATCH,
                    "%s holds %s%s as %s; this stock would arrive as %s".formatted(to, sku,
                            lotSuffix(lotNumber), destination.status(), arriving));
        }

        StockStatus before = source.status();
        source.moveOut(quantity);
        if (destination == null) {
            destination = StockItem.arrivedAs(source, to, quantity, arriving);
        } else {
            destination.moveIn(quantity);
        }
        stockItems.save(source);
        stockItems.save(destination);

        Instant now = clock.instant();
        StockMovement line = new StockMovement(Identifiers.newId(), StockMovement.MovementType.MOVE,
                sku, blankToNull(lotNumber), from, to, quantity.value(), before, arriving,
                referenceType, referenceId, reason, actorId, now);
        ledger.append(line);
        log.info("Moved {} x {}{} from {} to {} ({} -> {})", quantity, sku, lotSuffix(lotNumber), from, to,
                before, arriving);
        return toMove(line);
    }

    // ------------------------------------------------------------------ receipt

    /**
     * Always a new stock layer (one per receipt, V20260930001000): the receipt time tells it apart from
     * every other layer of the lot at the location, so nothing is merged into an older receipt.
     */
    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-receipt", resourceId = "#command.receiptLineId()")
    public StockMove receive(ReceiveStockCommand command) {
        WarehouseScope.requireLocation(command.locationCode());
        policies.lock(command.sku().code());
        Optional<StockMovement> done = ledger.findByReference(StockMovement.MovementType.RECEIPT,
                StockMovement.ReferenceType.GOODS_RECEIPT_LINE, command.receiptLineId());
        if (done.isPresent()) {
            return toMove(done.get());
        }
        LocationId location = new LocationId(command.locationCode());
        switch (locations.stateOf(location)) {
            case UNKNOWN -> throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + location);
            case UNUSABLE -> throw new BusinessException(ErrorCode.LOCATION_NOT_USABLE,
                    "%s cannot take stock now: it, its shelf or its warehouse is not active".formatted(location));
            case USABLE -> { }
        }
        String lot = blankToNull(command.lotNumber());
        Quantity quantity = Quantity.of(command.quantity());
        stockItems.save(StockItem.receiveInbound(command.sku(), location, lot, command.expiryDate(), quantity,
                command.receivedAt()));

        StockMovement line = new StockMovement(Identifiers.newId(), StockMovement.MovementType.RECEIPT,
                command.sku(), lot, null, location, quantity.value(), StockStatus.INBOUND,
                StockMovement.ReferenceType.GOODS_RECEIPT_LINE, command.receiptLineId(), null,
                command.actorId(), clock.instant());
        ledger.append(line);
        log.info("Received {} x {}{} into {}", quantity, command.sku(), lotSuffix(lot), location);
        return toMove(line);
    }

    // ------------------------------------------------------------------ adjustments

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-adjustment", resourceId = "#result?.adjustmentId()")
    public StockAdjustmentSummary requestAdjustment(RequestAdjustmentCommand command) {
        WarehouseScope.requireLocation(command.locationCode());
        LocationId location = new LocationId(command.locationCode());
        // The stock item must exist now; FOUND stock adds to a lot already recorded at the location.
        findOne(command.sku(), location, command.lotNumber())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_ITEM_NOT_FOUND,
                        "No %s%s at %s".formatted(command.sku(), lotSuffix(command.lotNumber()), location)));
        Instant now = clock.instant();
        StockAdjustment adjustment = StockAdjustment.request(
                adjustments.nextNumber(LocalDate.ofInstant(now, BUSINESS_ZONE)), location, command.sku(),
                blankToNull(command.lotNumber()), command.quantityDelta(),
                AdjustmentReason.valueOf(command.reason().name()), command.note(), command.requestedBy(), now);
        return toSummary(adjustments.save(adjustment));
    }

    @Override
    @Auditable(action = AuditAction.APPROVE, resourceType = "stock-adjustment", resourceId = "#adjustmentId")
    public StockAdjustmentSummary approve(UUID adjustmentId, UUID approverId) {
        StockAdjustment adjustment = load(adjustmentId);
        policies.lock(adjustment.sku().code());
        StockItem view = findOne(adjustment.sku(), adjustment.location(), adjustment.lotNumber())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_ITEM_NOT_FOUND,
                        "The stock of adjustment %s no longer exists".formatted(adjustment.number())));
        StockItem item = stockItems.findByIdForUpdate(view.id()).orElseThrow();
        Instant now = clock.instant();
        adjustment.approveAndPost(approverId, item, now);
        stockItems.save(item);
        StockAdjustment saved = adjustments.save(adjustment);

        int delta = adjustment.quantityDelta();
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.ADJUSTMENT,
                adjustment.sku(), adjustment.lotNumber(),
                delta < 0 ? adjustment.location() : null, delta > 0 ? adjustment.location() : null,
                Math.abs(delta), item.status(), StockMovement.ReferenceType.STOCK_ADJUSTMENT, adjustment.id(),
                adjustment.reason() + (adjustment.note() == null ? "" : ": " + adjustment.note()),
                approverId, now));
        log.info("Posted adjustment {}: {} {} at {}", adjustment.number(), delta, adjustment.sku(), adjustment.location());
        return toSummary(saved);
    }

    @Override
    @Auditable(action = AuditAction.REJECT, resourceType = "stock-adjustment", resourceId = "#adjustmentId")
    public StockAdjustmentSummary reject(UUID adjustmentId, UUID approverId, String reason) {
        StockAdjustment adjustment = load(adjustmentId);
        adjustment.reject(approverId, reason, clock.instant());
        return toSummary(adjustments.save(adjustment));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StockAdjustmentSummary> findAdjustment(UUID adjustmentId) {
        // Out of the caller's warehouses reads as not found: its existence is not theirs to know.
        return adjustments.findById(adjustmentId)
                .filter(adjustment -> inScope(adjustment.location().code()))
                .map(StockOperationsServiceImpl::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<StockAdjustmentSummary> adjustments(StockAdjustmentStatus status, String sku, String location,
                                                           int page, int size, String sort) {
        var criteria = new StockAdjustmentSearch.Criteria(
                status == null ? null : AdjustmentStatus.valueOf(status.name()), sku, location);
        return Pages.toResponse(adjustmentSearch.search(criteria, Pages.of(page, size, ADJUSTMENT_SORT.parse(sort)))
                .map(StockOperationsServiceImpl::toSummary));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LedgerLine> ledger(String sku, String location, StockMovement.MovementType type,
                                           int page, int size) {
        var pageable = Pages.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"));
        return Pages.toResponse(ledgerSearch.search(new StockLedgerSearch.Criteria(sku, location, type), pageable)
                .map(StockOperationsServiceImpl::toLine));
    }

    // ------------------------------------------------------------------ helpers

    private StockAdjustment load(UUID adjustmentId) {
        StockAdjustment adjustment = adjustments.findById(adjustmentId).orElseThrow(() ->
                new BusinessException(ErrorCode.STOCK_ADJUSTMENT_NOT_FOUND, "No stock adjustment " + adjustmentId));
        WarehouseScope.requireLocation(adjustment.location().code());
        return adjustment;
    }

    private static boolean inScope(String locationCode) {
        return WarehouseScope.restriction()
                .map(allowed -> allowed.prefixes().contains(WarehouseScope.prefixOf(locationCode)))
                .orElse(true);
    }

    /** {@link #findOne}, or the one layer received at {@code receivedAt} when it is given. */
    private Optional<StockItem> findLayer(Sku sku, LocationId location, String lotNumber, Instant receivedAt) {
        if (receivedAt == null) {
            return findOne(sku, location, lotNumber);
        }
        String lot = blankToNull(lotNumber);
        return stockItems.findBySkuAndLocation(sku, location).stream()
                .filter(item -> Objects.equals(item.lotNumber(), lot) && receivedAt.equals(item.receivedAt()))
                .findFirst();
    }

    private Optional<StockItem> findOne(Sku sku, LocationId location, String lotNumber) {
        String lot = blankToNull(lotNumber);
        var matches =
                stockItems.findBySkuAndLocation(sku, location).stream()
                .filter(item -> Objects.equals(item.lotNumber(), lot))
                        .toList();
        if (matches.size() > 1) {
            // The existing move/adjustment request has no receipt/serial selector. Never pick
            // an arbitrary layer now that canonical policy permits several at one location.
            throw new BusinessException(ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT);
        }
        return matches.stream()
                .findFirst();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String lotSuffix(String lot) {
        return lot == null || lot.isBlank() ? "" : " lot " + lot;
    }

    private static StockMove toMove(StockMovement m) {
        return new StockMove(m.id(), m.sku().code(), m.lotNumber(), m.from() == null ? null : m.from().code(),
                m.to() == null ? null : m.to().code(), m.quantity(), m.occurredAt());
    }

    private static LedgerLine toLine(StockMovement m) {
        return new LedgerLine(m.id(), m.type(), m.sku().code(), m.lotNumber(),
                m.from() == null ? null : m.from().code(), m.to() == null ? null : m.to().code(),
                m.quantity(), m.status() == null ? null : m.status().name(),
                m.toStatus() == null ? null : m.toStatus().name(), m.referenceType().name(), m.referenceId(), m.reason(), m.actorId(), m.occurredAt());
    }

    static StockAdjustmentSummary toSummary(StockAdjustment a) {
        return new StockAdjustmentSummary(a.id(), a.number(), a.location().code(), a.sku().code(), a.lotNumber(),
                a.quantityDelta(), StockAdjustmentReason.valueOf(a.reason().name()), a.note(),
                StockAdjustmentStatus.valueOf(a.status().name()), a.requestedBy(), a.requestedAt(),
                a.decidedBy(), a.decidedAt(), a.rejectionReason(), a.postedAt(), a.version());
    }
}
