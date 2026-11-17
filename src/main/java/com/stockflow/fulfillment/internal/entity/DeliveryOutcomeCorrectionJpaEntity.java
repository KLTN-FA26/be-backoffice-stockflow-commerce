package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A shipment's outcome corrected by the warehouse manager because the carrier reported it wrongly (table
 * {@code fulfillment.delivery_outcome_correction}; kltn-docs 09 §4.3 step 5, E17): from what, to what, why, who, when.
 * Append-only. A shipment with one takes no carrier report for its outcome any more (the error list does).
 */
@Entity
@Table(name = "delivery_outcome_correction", schema = "fulfillment")
public class DeliveryOutcomeCorrectionJpaEntity extends BaseEntity {

    @Column(name = "shipment_id", nullable = false, updatable = false) private UUID shipmentId;
    @Column(name = "from_status", nullable = false, length = 24, updatable = false) private String fromStatus;
    @Column(name = "to_status", nullable = false, length = 24, updatable = false) private String toStatus;
    @Column(name = "failure_reason", length = 32, updatable = false) private String failureReason;
    @Column(name = "failure_note", length = 1000, updatable = false) private String failureNote;
    @Column(name = "reason", nullable = false, length = 500, updatable = false) private String reason;
    @Column(name = "corrected_by", nullable = false, updatable = false) private UUID correctedBy;
    @Column(name = "corrected_at", nullable = false, updatable = false) private Instant correctedAt;

    protected DeliveryOutcomeCorrectionJpaEntity() {
    }

    public DeliveryOutcomeCorrectionJpaEntity(UUID id, UUID shipmentId, String fromStatus, String toStatus,
                                              String failureReason, String failureNote, String reason,
                                              UUID correctedBy, Instant correctedAt) {
        super(id);
        this.shipmentId = shipmentId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.failureReason = failureReason;
        this.failureNote = failureNote;
        this.reason = reason;
        this.correctedBy = correctedBy;
        this.correctedAt = correctedAt;
    }

    public UUID getShipmentId() { return shipmentId; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getFailureReason() { return failureReason; }
    public String getFailureNote() { return failureNote; }
    public String getReason() { return reason; }
    public UUID getCorrectedBy() { return correctedBy; }
    public Instant getCorrectedAt() { return correctedAt; }
}
