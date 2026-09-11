package com.stockflow.procurement.internal.entity;

import com.stockflow.procurement.internal.domain.QcOutcome;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of a QC result (table {@code procurement.qc_result}). Not the domain model. STARTER ENTITY.
 *
 * <p>{@code goodsReceiptId} is a same-schema reference.</p>
 */
@Entity
@Table(name = "qc_result", schema = "procurement")
public class QcResultJpaEntity extends BaseEntity {

    @Column(name = "goods_receipt_id", nullable = false)
    private UUID goodsReceiptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private QcOutcome outcome;

    @Column(name = "quantity_passed", nullable = false)
    private int quantityPassed;

    @Column(name = "quantity_failed", nullable = false)
    private int quantityFailed;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "inspected_at")
    private Instant inspectedAt;

    protected QcResultJpaEntity() {
    }

    public QcResultJpaEntity(UUID id, UUID goodsReceiptId, QcOutcome outcome, int quantityPassed,
                             int quantityFailed, String notes, Instant inspectedAt) {
        super(id);
        this.goodsReceiptId = goodsReceiptId;
        this.outcome = outcome;
        this.quantityPassed = quantityPassed;
        this.quantityFailed = quantityFailed;
        this.notes = notes;
        this.inspectedAt = inspectedAt;
    }

    public UUID getGoodsReceiptId() { return goodsReceiptId; }
    public QcOutcome getOutcome() { return outcome; }
    public int getQuantityPassed() { return quantityPassed; }
    public int getQuantityFailed() { return quantityFailed; }
    public String getNotes() { return notes; }
    public Instant getInspectedAt() { return inspectedAt; }
}
