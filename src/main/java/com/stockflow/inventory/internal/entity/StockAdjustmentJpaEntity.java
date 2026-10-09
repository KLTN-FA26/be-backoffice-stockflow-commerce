package com.stockflow.inventory.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.inventory.internal.domain.AdjustmentReason;
import com.stockflow.inventory.internal.domain.AdjustmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** {@code inventory.stock_adjustment}. The request fields are immutable; only the decision moves. */
@Entity
@Table(name = "stock_adjustment", schema = "inventory")
public class StockAdjustmentJpaEntity extends BaseEntity {

    @Column(name = "adjustment_number", nullable = false, length = 30, updatable = false)
    private String adjustmentNumber;

    @Column(name = "location_code", nullable = false, length = 64, updatable = false)
    private String locationCode;

    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "lot_number", length = 64, updatable = false)
    private String lotNumber;

    @Column(name = "quantity_delta", nullable = false, updatable = false)
    private int quantityDelta;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 24, updatable = false)
    private AdjustmentReason reasonCode;

    @Column(name = "note", length = 1000, updatable = false)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AdjustmentStatus status;

    /** Set only by a cycle count's variance line; a manual adjustment leaves it null. */
    @Column(name = "cycle_count_line_id", updatable = false)
    private UUID cycleCountLineId;

    @Column(name = "requested_by", nullable = false, updatable = false)
    private UUID requestedBy;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "posted_at")
    private Instant postedAt;

    protected StockAdjustmentJpaEntity() {
    }

    public StockAdjustmentJpaEntity(UUID id, String adjustmentNumber, String locationCode, String sku,
                                    String lotNumber, int quantityDelta, AdjustmentReason reasonCode,
                                    String note, UUID requestedBy) {
        super(id);
        this.adjustmentNumber = adjustmentNumber;
        this.locationCode = locationCode;
        this.sku = sku;
        this.lotNumber = lotNumber;
        this.quantityDelta = quantityDelta;
        this.reasonCode = reasonCode;
        this.note = note;
        this.requestedBy = requestedBy;
        this.status = AdjustmentStatus.PENDING_APPROVAL;
    }

    public void decide(AdjustmentStatus status, UUID decidedBy, Instant decidedAt, String rejectionReason,
                       Instant postedAt) {
        this.status = status;
        this.decidedBy = decidedBy;
        this.decidedAt = decidedAt;
        this.rejectionReason = rejectionReason;
        this.postedAt = postedAt;
    }

    public String getAdjustmentNumber() { return adjustmentNumber; }
    public String getLocationCode() { return locationCode; }
    public String getSku() { return sku; }
    public String getLotNumber() { return lotNumber; }
    public int getQuantityDelta() { return quantityDelta; }
    public AdjustmentReason getReasonCode() { return reasonCode; }
    public String getNote() { return note; }
    public AdjustmentStatus getStatus() { return status; }
    public UUID getRequestedBy() { return requestedBy; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getPostedAt() { return postedAt; }
}
