package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.persistence.SortWhitelist;
import com.stockflow.inventory.api.MoveStockCommand;
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
        // Match policy edits/reservations: take the SKU lock before any stock-row locks.
        policies.lock(command.sku().code());
        StockMovement.ReferenceType referenceType = StockMovement.ReferenceType.valueOf(command.reference().name());
        UUID referenceId = command.referenceId() != null ? command.referenceId() : command.requestId();

        // Replay first: a retried move must not move the goods again.
        Optional<StockMovement> done = ledger.findByReference(StockMovement.MovementType.MOVE, referenceType, referenceId);
        if (done.isPresent()) {
            return toMove(done.get());
        }

        LocationId from = new LocationId(command.fromLocation());
        LocationId to = new LocationId(command.toLocation());
        if (from.equals(to)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A move needs two different locations");
        }
        if (!locations.exists(to)) {
            throw new BusinessException(ErrorCode.LOCATION_NOT_FOUND, "No location " + to);
        }
        Quantity quantity = Quantity.of(command.quantity());

        StockItem sourceView = findOne(command.sku(), from, command.lotNumber())
                .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_ITEM_NOT_FOUND,
                        "No %s%s at %s".formatted(command.sku(), lotSuffix(command.lotNumber()), from)));
        Optional<StockItem> destinationView = findOne(command.sku(), to, command.lotNumber());

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

        if (destination != null && !destination.canReceiveFrom(source)) {
            throw new BusinessException(
                    destination.status() == source.status()
                            ? ErrorCode.INVENTORY_POLICY_STOCK_CONFLICT
                            : ErrorCode.STOCK_STATUS_MISMATCH,
                    "%s holds %s%s as %s; this stock is %s".formatted(to, command.sku(),
                            lotSuffix(command.lotNumber()), destination.status(), source.status()));
        }

        source.moveOut(quantity);
        if (destination == null) {
            destination = StockItem.arrivedFrom(source, to, quantity);
        } else {
            destination.moveIn(quantity);
        }
        stockItems.save(source);
        stockItems.save(destination);

        Instant now = clock.instant();
        StockMovement line = new StockMovement(Identifiers.newId(), StockMovement.MovementType.MOVE,
                command.sku(), command.lotNumber(), from, to, quantity.value(), source.status(),
                referenceType, referenceId, null, command.actorId(), now);
        ledger.append(line);
        log.info("Moved {} x {}{} from {} to {}", quantity, command.sku(), lotSuffix(command.lotNumber()), from, to);
        return toMove(line);
    }

    // ------------------------------------------------------------------ adjustments

    @Override
    @Auditable(action = AuditAction.CREATE, resourceType = "stock-adjustment", resourceId = "#result?.adjustmentId()")
    public StockAdjustmentSummary requestAdjustment(RequestAdjustmentCommand command) {
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
        return adjustments.findById(adjustmentId).map(StockOperationsServiceImpl::toSummary);
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
        return adjustments.findById(adjustmentId).orElseThrow(() ->
                new BusinessException(ErrorCode.STOCK_ADJUSTMENT_NOT_FOUND, "No stock adjustment " + adjustmentId));
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
        return new StockMove(m.id(), m.sku().code(), m.lotNumber(), m.from().code(), m.to().code(),
                m.quantity(), m.occurredAt());
    }

    private static LedgerLine toLine(StockMovement m) {
        return new LedgerLine(m.id(), m.type(), m.sku().code(), m.lotNumber(),
                m.from() == null ? null : m.from().code(), m.to() == null ? null : m.to().code(),
                m.quantity(), m.status() == null ? null : m.status().name(),
                m.referenceType().name(), m.referenceId(), m.reason(), m.actorId(), m.occurredAt());
    }

    static StockAdjustmentSummary toSummary(StockAdjustment a) {
        return new StockAdjustmentSummary(a.id(), a.number(), a.location().code(), a.sku().code(), a.lotNumber(),
                a.quantityDelta(), StockAdjustmentReason.valueOf(a.reason().name()), a.note(),
                StockAdjustmentStatus.valueOf(a.status().name()), a.requestedBy(), a.requestedAt(),
                a.decidedBy(), a.decidedAt(), a.rejectionReason(), a.postedAt(), a.version());
    }
}
