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
    private final long version;

    public StockAdjustment(UUID id, String number, LocationId location, Sku sku, String lotNumber,
                           int quantityDelta, AdjustmentReason reason, String note, UUID requestedBy,
                           Instant requestedAt, AdjustmentStatus status, UUID decidedBy, Instant decidedAt,
                           String rejectionReason, Instant postedAt, long version) {
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
        this.version = version;
        if (quantityDelta == 0) {
            throw new IllegalArgumentException("An adjustment changes the quantity");
        }
        if (this.reason == AdjustmentReason.OTHER && this.note == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "An adjustment with reason OTHER must say why in the note");
        }
    }

    public static StockAdjustment request(String number, LocationId location, Sku sku, String lotNumber,
                                          int quantityDelta, AdjustmentReason reason, String note,
                                          UUID requestedBy, Instant now) {
        return new StockAdjustment(Identifiers.newId(), number, location, sku, lotNumber, quantityDelta,
                reason, note, requestedBy, now, AdjustmentStatus.PENDING_APPROVAL, null, null, null, null, 0L);
    }

    /**
     * Approve and post onto {@code stockItem}, which the caller has locked.
     *
     * @throws BusinessException {@code ADJUSTMENT_SELF_APPROVAL} when the approver asked for it;
     *         {@code INVALID_ADJUSTMENT_TRANSITION} when it was already decided
     */
    public void approveAndPost(UUID approverId, StockItem stockItem, Instant now) {
        requirePending();
        Objects.requireNonNull(approverId, "approverId");
        if (approverId.equals(requestedBy)) {
            throw new BusinessException(ErrorCode.ADJUSTMENT_SELF_APPROVAL,
                    "Adjustment %s cannot be approved by the person who requested it".formatted(number));
        }
        if (!stockItem.sku().equals(sku) || !stockItem.location().equals(location)
                || !Objects.equals(stockItem.lotNumber(), lotNumber)) {
            throw new IllegalArgumentException("Adjustment %s is for another stock item".formatted(number));
        }
        stockItem.adjustBy(quantityDelta);
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

    private void requirePending() {
        if (status.isDecided()) {
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
    public long version() { return version; }
}
