package com.stockflow.fulfillment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The failure reason of a shipment corrected by the warehouse manager once they learnt the real one (table
 * {@code fulfillment.failure_reason_correction}; kltn-docs 09 edge "hoàn hàng khi còn Handed Over ... Quản lý kho sửa
 * lý do thất bại nếu biết lý do thật"): the reason and note before and after, why, who, when. Append-only. Unlike an
 * outcome correction ({@link DeliveryOutcomeCorrectionJpaEntity}) it changes no status, so the carrier's later reports
 * about the shipment still apply.
 */
@Entity
@Table(name = "failure_reason_correction", schema = "fulfillment")
public class FailureReasonCorrectionJpaEntity extends BaseEntity {

    @Column(name = "shipment_id", nullable = false, updatable = false) private UUID shipmentId;
    @Column(name = "from_reason", nullable = false, length = 32, updatable = false) private String fromReason;
    @Column(name = "from_note", length = 500, updatable = false) private String fromNote;
    @Column(name = "to_reason", nullable = false, length = 32, updatable = false) private String toReason;
    @Column(name = "to_note", length = 500, updatable = false) private String toNote;
    @Column(name = "reason", nullable = false, length = 500, updatable = false) private String reason;
    @Column(name = "corrected_by", nullable = false, updatable = false) private UUID correctedBy;
    @Column(name = "corrected_at", nullable = false, updatable = false) private Instant correctedAt;

    protected FailureReasonCorrectionJpaEntity() {
    }

    @SuppressWarnings("java:S107")
    public FailureReasonCorrectionJpaEntity(UUID id, UUID shipmentId, String fromReason, String fromNote,
                                            String toReason, String toNote, String reason, UUID correctedBy,
                                            Instant correctedAt) {
        super(id);
        this.shipmentId = shipmentId;
        this.fromReason = fromReason;
        this.fromNote = fromNote;
        this.toReason = toReason;
        this.toNote = toNote;
        this.reason = reason;
        this.correctedBy = correctedBy;
        this.correctedAt = correctedAt;
    }

    public UUID getShipmentId() { return shipmentId; }
    public String getFromReason() { return fromReason; }
    public String getFromNote() { return fromNote; }
    public String getToReason() { return toReason; }
    public String getToNote() { return toNote; }
    public String getReason() { return reason; }
    public UUID getCorrectedBy() { return correctedBy; }
    public Instant getCorrectedAt() { return correctedAt; }
}
