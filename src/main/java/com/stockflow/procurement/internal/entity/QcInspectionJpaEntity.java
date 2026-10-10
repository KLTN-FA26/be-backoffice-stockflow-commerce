package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.procurement.internal.domain.QcOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code procurement.qc_inspections} (replaces the old {@code qc_result}, unmapped until C4). Written once. */
@Entity
@Table(name = "qc_inspections", schema = "procurement")
public class QcInspectionJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_line_id", nullable = false, updatable = false)
    private GoodsReceiptLineJpaEntity line;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16, updatable = false)
    private QcOutcome outcome;

    @Column(name = "quantity", nullable = false, precision = 18, scale = 3, updatable = false)
    private BigDecimal quantity;

    @Column(name = "target_location_id", updatable = false)
    private UUID targetLocationId;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    @Column(name = "inspected_by", nullable = false, updatable = false)
    private UUID inspectedBy;

    @Column(name = "inspected_at", nullable = false, updatable = false)
    private Instant inspectedAt;

    protected QcInspectionJpaEntity() {
    }

    public QcInspectionJpaEntity(UUID id, QcOutcome outcome, BigDecimal quantity, UUID targetLocationId, String reason,
                                 UUID inspectedBy, Instant inspectedAt) {
        super(id);
        this.outcome = outcome;
        this.quantity = quantity;
        this.targetLocationId = targetLocationId;
        this.reason = reason;
        this.inspectedBy = inspectedBy;
        this.inspectedAt = inspectedAt;
    }

    void attachTo(GoodsReceiptLineJpaEntity owner) {
        this.line = owner;
    }

    public QcOutcome getOutcome() { return outcome; }
    public BigDecimal getQuantity() { return quantity; }
    public UUID getTargetLocationId() { return targetLocationId; }
    public String getReason() { return reason; }
    public UUID getInspectedBy() { return inspectedBy; }
    public Instant getInspectedAt() { return inspectedAt; }
}
