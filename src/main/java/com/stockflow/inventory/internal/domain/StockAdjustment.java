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
 * A request to change the quantity of one stock item by hand, and its four-eyes decision
 * (SCRUM-145, docs 06 adjustment rules).
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
     * The hold whose units this write-off takes (BR-PRD-10: printed goods of a cancelled order, still held so nobody
     * can take them); approving it closes the hold as it deducts the units. Null for every other write-off.
     */
    private UUID reservationId;
    /**
     * The carton whose contents this adjustment changes (#125, SCRUM-164); null for loose stock. A carton is adjusted
     * as one: approval spreads the units over its rows and changes the carton's state once, because the database
     * refuses a carton whose state disagrees with what its rows hold.
     */
    private StockPackageId stockPackageId;
    /** The cycle count line whose variance this is (SCRUM-164); null for an adjustment a person typed in. */
    private UUID cycleCountLineId;

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

    /** The adjustment changes the contents of this carton (set once, when asked for). */
    public StockAdjustment inPackage(StockPackageId stockPackageId) {
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

    public static StockAdjustment request(String number, LocationId location, Sku sku, String lotNumber,
                                          int quantityDelta, AdjustmentReason reason, String note,
                                          UUID requestedBy, Instant now) {
        return new StockAdjustment(Identifiers.newId(), number, location, sku, lotNumber, quantityDelta,
                reason, note, requestedBy, now, AdjustmentStatus.PENDING_APPROVAL, null, null, null, null, null, 0L);
    }

    /**
     * Approve and post onto {@code stockItem}, which the caller has locked. Loose stock only: a carton's adjustment
     * is approved with {@link #approveForCarton}.
     *
     * @throws BusinessException {@code ADJUSTMENT_SELF_APPROVAL} when the approver asked for it;
     *         {@code INVALID_ADJUSTMENT_TRANSITION} when it was already decided
     */
    public void approveAndPost(UUID approverId, StockItem stockItem, Instant now) {
        requireApprovableBy(approverId);
        if (stockPackageId != null) {
            throw new IllegalStateException("Adjustment %s is about carton %s; it is approved for the carton"
                    .formatted(number, stockPackageId.value()));
        }
        if (!stockItem.sku().equals(sku) || !stockItem.location().equals(location)
                || !Objects.equals(stockItem.lotNumber(), lotNumber)) {
            throw new IllegalArgumentException("Adjustment %s is for another stock item".formatted(number));
        }
        stockItem.adjustBy(quantityDelta);
        posted(approverId, now);
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

    private void requireApprovableBy(UUID approverId) {
        requirePending();
        Objects.requireNonNull(approverId, "approverId");
        if (approverId.equals(requestedBy)) {
            throw new BusinessException(ErrorCode.ADJUSTMENT_SELF_APPROVAL,
                    "Adjustment %s cannot be approved by the person who requested it".formatted(number));
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
