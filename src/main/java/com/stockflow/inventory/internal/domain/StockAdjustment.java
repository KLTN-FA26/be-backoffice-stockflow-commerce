package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A request to write one LPN carton off whole or restock it whole, and its four-eyes decision (SCRUM-145; kltn-docs
 * 20 §4.3, BR-04, BR-05; team plan 4C-3) - {@link #requestCarton}. A row in no carton, which only the paths not yet in
 * cartons still write, is adjusted in units ({@link #request}).
 *
 * <p>Nobody writes stock up or down on their own say-so: the person who asks is never the person
 * who approves ({@code ck_stock_adjustment_four_eyes} says it again in the table). Approving posts
 * — the stock item's {@code onHand} moves in the same transaction — so an approved adjustment that
 * has not touched stock cannot exist.</p>
 *
 * <p>Until it is decided, the person who asked may take it back ({@link #withdraw}); nobody else
 * may, so a manager cannot make a request disappear without deciding it.</p>
 */
public final class StockAdjustment extends AggregateRoot {

    private final UUID id;
    private final String number;
    private final LocationId location;
    private final Sku sku;
    private final String lotNumber;
    private final int quantityDelta;
    private final AdjustmentReason reason;
    private final String note;
    private final UUID requestedBy;
    private final Instant requestedAt;

    private AdjustmentStatus status;
    private UUID decidedBy;
    private Instant decidedAt;
    private String rejectionReason;
    private Instant postedAt;
    private Instant withdrawnAt;
    private final long version;
    /** The receipt layer, when the location holds several layers of the lot; null picks the only one. */
    private Instant receivedAt;
    /**
     * Legacy: the hold whose units this write-off takes (BR-PRD-10, while in-house production existed: printed goods of
     * a cancelled order, still held so nobody could take them); approving it closes the hold as it deducts the units.
     * Nothing asks for one any more; null for every write-off since.
     */
    private UUID reservationId;
    /**
     * The carton this adjustment writes off or restocks whole (#125, SCRUM-164, team plan 4C-3); null for loose stock.
     * Approval changes the carton's row and its state once, because the database refuses a carton whose state
     * disagrees with what its row holds.
     */
    private StockPackageId stockPackageId;
    /** The cycle count line whose variance this is (SCRUM-164); null for an adjustment a person typed in. */
    private UUID cycleCountLineId;
    /**
     * Who entered the figure of that count line (WF-05 fix plan, section 8); null for an adjustment a person typed in,
     * or a line nobody counted. Kept on the adjustment rather than read from the line when it is decided, so the rule
     * "the counter does not decide" is a fact of this row - {@code ck_stock_adjustment_counter_not_decider} states it
     * again in the table - and the ledger line of its posting can name the counter.
     */
    private UUID countedBy;

    public StockAdjustment(UUID id, String number, LocationId location, Sku sku, String lotNumber,
                           int quantityDelta, AdjustmentReason reason, String note, UUID requestedBy,
                           Instant requestedAt, AdjustmentStatus status, UUID decidedBy, Instant decidedAt,
                           String rejectionReason, Instant postedAt, Instant withdrawnAt, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.number = Objects.requireNonNull(number, "number");
        this.location = Objects.requireNonNull(location, "location");
        this.sku = Objects.requireNonNull(sku, "sku");
        this.lotNumber = lotNumber;
        this.quantityDelta = quantityDelta;
        this.reason = Objects.requireNonNull(reason, "reason");
        this.note = note == null || note.isBlank() ? null : note.trim();
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy");
        this.requestedAt = requestedAt;
        this.status = Objects.requireNonNull(status, "status");
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.rejectionReason = rejectionReason;
        this.postedAt = postedAt;
        this.withdrawnAt = withdrawnAt;
        this.version = version;
        if (quantityDelta == 0) {
            throw new IllegalArgumentException("An adjustment changes the quantity");
        }
        if (this.reason == AdjustmentReason.OTHER && this.note == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "An adjustment with reason OTHER must say why in the note");
        }
        // ck_stock_adjustment_writes_down says it again in the table.
        if (this.reason.onlyWritesDown() && quantityDelta > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "An adjustment with reason %s can only reduce stock".formatted(this.reason));
        }
    }

    /** The adjustment names one receipt layer of the lot at its location (set once, when asked for). */
    public StockAdjustment atLayer(Instant receivedAt) {
        this.receivedAt = receivedAt;
        return this;
    }

    public Instant receivedAt() { return receivedAt; }

    /** The adjustment takes the units of this hold (set once, when asked for). */
    public StockAdjustment forHold(UUID reservationId) {
        this.reservationId = reservationId;
        return this;
    }

    public UUID reservationId() { return reservationId; }

    /**
     * The adjustment changes the contents of this carton (set once, when asked for). A carton is adjusted for the
     * reasons a person gives by hand, or as a count's variance (kltn-docs 20 BR-04; review P-2): SCRAP, SAMPLE and the
     * rest wrote down loose units. {@code tg_stock_adjustment_whole_carton} states it again.
     */
    public StockAdjustment inPackage(StockPackageId stockPackageId) {
        if (reason != AdjustmentReason.COUNT_VARIANCE && !reason.askableByHand()) {
            throw new BusinessException(ErrorCode.STOCK_ADJUSTMENT_REASON_NOT_FOR_CARTON,
                    "A carton is adjusted for DAMAGED, LOST, FOUND, DATA_CORRECTION or OTHER, not " + reason);
        }
        this.stockPackageId = stockPackageId;
        return this;
    }

    public StockPackageId stockPackageId() { return stockPackageId; }

    /**
     * The adjustment is the variance of this count line (set once, when asked for). The table refuses a
     * {@link AdjustmentReason#COUNT_VARIANCE} without one ({@code ck_stock_adjustment_count_variance}).
     */
    public StockAdjustment forCountLine(UUID cycleCountLineId) {
        this.cycleCountLineId = cycleCountLineId;
        return this;
    }

    public UUID cycleCountLineId() { return cycleCountLineId; }

    /**
     * The person who counted the line this variance comes from (set once, when asked for). Only a count's variance has
     * a counter ({@code ck_stock_adjustment_counted_by_count}).
     */
    public StockAdjustment countedBy(UUID countedBy) {
        if (countedBy != null && cycleCountLineId == null) {
            throw new IllegalArgumentException("Only the variance of a count line has a counter");
        }
        this.countedBy = countedBy;
        return this;
    }

    public UUID countedBy() { return countedBy; }

    /**
     * A carton written off whole, or a LOST carton restocked whole (kltn-docs 20 §4.3; team plan 4C-3): the delta is
     * its standard count either way, the reason one of BR-04's, the location the carton's.
     * {@code tg_stock_adjustment_whole_carton} says it again.
     *
     * @param units    the carton's standard count
     * @throws BusinessException {@code VALIDATION_FAILED} for a reason of the history, or {@code COUNT_VARIANCE}
     *         asked by hand ({@code countLineId} null)
     */
    public static StockAdjustment requestCarton(String number, StockPackageId carton, LocationId location, Sku sku,
                                                String lotNumber, AdjustmentDirection direction, int units,
                                                AdjustmentReason reason, String note, UUID countLineId,
                                                UUID requestedBy, Instant now) {
        Objects.requireNonNull(carton, "carton");
        if (units <= 0) {
            throw new IllegalArgumentException("A carton holds at least one unit");
        }
        if (reason == AdjustmentReason.COUNT_VARIANCE ? countLineId == null : !reason.askableByHand()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, reason == AdjustmentReason.COUNT_VARIANCE
                    ? "A count variance comes from a cycle count"
                    : "A carton is adjusted for DAMAGED, LOST, FOUND, DATA_CORRECTION or OTHER, not " + reason);
        }
        int delta = direction == AdjustmentDirection.WRITE_OFF ? -units : units;
        return request(number, location, sku, lotNumber, delta, reason, note, requestedBy, now)
                .inPackage(carton).forCountLine(countLineId);
    }

    /**
     * A new adjustment, pending approval: loose stock (a row in no carton), or with {@link #inPackage} a carton written
     * off whole through {@code RequestAdjustmentCommand.lpn} (team plan 4D-1). A carton is otherwise asked for with
     * {@link #requestCarton} (4C-3).
     *
     * @throws BusinessException {@code VALIDATION_FAILED} for a {@link AdjustmentReason#legacy() legacy} reason: a
     *         print subcontractor's loss belongs to subcontracting, which went with purchasing (4A-8)
     */
    public static StockAdjustment request(String number, LocationId location, Sku sku, String lotNumber,
                                          int quantityDelta, AdjustmentReason reason, String note,
                                          UUID requestedBy, Instant now) {
        if (reason != null && reason.legacy()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, ("%s is a reason of past adjustments only: "
                    + "subcontracting is gone").formatted(reason));
        }
        return new StockAdjustment(Identifiers.newId(), number, location, sku, lotNumber, quantityDelta,
                reason, note, requestedBy, now, AdjustmentStatus.PENDING_APPROVAL, null, null, null, null, null, 0L);
    }

    /**
     * Approve a carton's adjustment, whose units the caller spreads over the carton's rows in the same transaction,
     * under the carton's lock. Which rows give or take the units, and what the carton becomes, depend on the carton as
     * it is at approval - not on anything this request knows - so the posting is the caller's; this decides. A
     * failure while posting rolls the decision back with it.
     *
     * @throws BusinessException {@code ADJUSTMENT_SELF_APPROVAL} when the approver asked for it;
     *         {@code INVALID_ADJUSTMENT_TRANSITION} when it was already decided
     */
    public void approveForCarton(UUID approverId, Instant now) {
        requireApprovableBy(approverId);
        if (stockPackageId == null) {
            throw new IllegalStateException("Adjustment %s is about loose stock; it is posted onto its stock row"
                    .formatted(number));
        }
        posted(approverId, now);
    }

    /**
     * Four eyes, deciding side: the variance of a count line is not decided by the person who counted the line - the
     * figure and its approval are two people, whoever requested the adjustment (the submitter). Checked before any
     * stock is looked at, after the adjustment is known to be still pending.
     *
     * <p>The counter is the one kept on the adjustment; {@code lineCounter} is what the count line says, used only
     * for an adjustment asked before the counter was kept on it.</p>
     *
     * @param lineCounter who entered the line's figure; null when the line was never counted (no one to refuse)
     * @throws BusinessException {@code ADJUSTMENT_SELF_APPROVAL}; {@code INVALID_ADJUSTMENT_TRANSITION} when decided
     */
    public void requireNotCountedBy(UUID decider, UUID lineCounter) {
        requirePending();
        Objects.requireNonNull(decider, "decider");
        UUID counter = countedBy != null ? countedBy : lineCounter;
        if (counter != null && counter.equals(decider)) {
            throw new BusinessException(ErrorCode.ADJUSTMENT_SELF_APPROVAL,
                    "Adjustment %s cannot be decided by the person who counted the line it comes from".formatted(number));
        }
    }

    private void requireApprovableBy(UUID approverId) {
        requirePending();
        Objects.requireNonNull(approverId, "approverId");
        if (approverId.equals(requestedBy)) {
            throw new BusinessException(ErrorCode.ADJUSTMENT_SELF_APPROVAL,
                    "Adjustment %s cannot be approved by the person who requested it".formatted(number));
        }
        requireNotTheCounter(approverId);
    }

    /** ck_stock_adjustment_counter_not_decider: whoever counted the line neither approves nor rejects its variance. */
    private void requireNotTheCounter(UUID decider) {
        if (decider.equals(countedBy)) {
            throw new BusinessException(ErrorCode.ADJUSTMENT_SELF_APPROVAL,
                    "Adjustment %s cannot be decided by the person who counted the line it comes from".formatted(number));
        }
    }

    private void posted(UUID approverId, Instant now) {
        this.status = AdjustmentStatus.POSTED;
        this.decidedBy = approverId;
        this.decidedAt = now;
        this.postedAt = now;
    }

    public void reject(UUID approverId, String reason, Instant now) {
        requirePending();
        Objects.requireNonNull(approverId, "approverId");
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A rejection must say why");
        }
        if (approverId.equals(requestedBy)) {
            throw new BusinessException(ErrorCode.ADJUSTMENT_SELF_APPROVAL,
                    "Adjustment %s cannot be decided by the person who requested it".formatted(number));
        }
        requireNotTheCounter(approverId);
        this.status = AdjustmentStatus.REJECTED;
        this.decidedBy = approverId;
        this.decidedAt = now;
        this.rejectionReason = reason.trim();
    }

    /**
     * The requester takes back a request nobody has decided yet. Stock was never touched, so there
     * is nothing to undo.
     *
     * <p>Not a {@code COUNT_VARIANCE}: it is the variance of a submitted count, raised by the system on the counter's
     * behalf, and the count stays COMPLETED whatever happens to it. Withdrawn, it would leave a completed count whose
     * variance nobody decided - the slot free for a new count while the doubt it recorded was never settled. It is
     * decided by approval or rejection (and a rejected one is recounted).</p>
     *
     * @throws BusinessException {@code ADJUSTMENT_NOT_REQUESTER} when someone else tries;
     *         {@code INVALID_ADJUSTMENT_TRANSITION} when it is no longer pending;
     *         {@code COUNT_VARIANCE_NOT_WITHDRAWABLE} for a count's variance
     */
    public void withdraw(UUID userId, Instant now) {
        requirePending();
        if (reason == AdjustmentReason.COUNT_VARIANCE) {
            throw new BusinessException(ErrorCode.COUNT_VARIANCE_NOT_WITHDRAWABLE,
                    "Adjustment %s is a count's variance: it is approved or rejected, not withdrawn".formatted(number));
        }
        if (!requestedBy.equals(Objects.requireNonNull(userId, "userId"))) {
            throw new BusinessException(ErrorCode.ADJUSTMENT_NOT_REQUESTER,
                    "Only the person who requested adjustment %s can withdraw it".formatted(number));
        }
        this.status = AdjustmentStatus.WITHDRAWN;
        this.withdrawnAt = now;
    }

    private void requirePending() {
        if (status.isClosed()) {
            throw new BusinessException(ErrorCode.INVALID_ADJUSTMENT_TRANSITION,
                    "Adjustment %s is already %s".formatted(number, status));
        }
    }

    public UUID id() { return id; }
    public String number() { return number; }
    public LocationId location() { return location; }
    public Sku sku() { return sku; }
    public String lotNumber() { return lotNumber; }
    public int quantityDelta() { return quantityDelta; }
    public AdjustmentReason reason() { return reason; }
    public String note() { return note; }
    public UUID requestedBy() { return requestedBy; }
    public Instant requestedAt() { return requestedAt; }
    public AdjustmentStatus status() { return status; }
    public UUID decidedBy() { return decidedBy; }
    public Instant decidedAt() { return decidedAt; }
    public String rejectionReason() { return rejectionReason; }
    public Instant postedAt() { return postedAt; }
    public Instant withdrawnAt() { return withdrawnAt; }
    public long version() { return version; }
}
