package com.stockflow.procurement.internal.entity;

import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/** JPA mapping of a goods receipt (table {@code procurement.goods_receipt}). Not the domain model. STARTER ENTITY. */
@Entity
@Table(name = "goods_receipt", schema = "procurement",
        uniqueConstraints = @UniqueConstraint(name = "uk_goods_receipt_number", columnNames = "receipt_number"))
public class GoodsReceiptJpaEntity extends BaseEntity {

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "receipt_number", nullable = false, length = 64)
    private String receiptNumber;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private GoodsReceiptStatus status;

    protected GoodsReceiptJpaEntity() {
    }

    public GoodsReceiptJpaEntity(UUID id, UUID purchaseOrderId, String receiptNumber,
                                 Instant receivedAt, GoodsReceiptStatus status) {
        super(id);
        this.purchaseOrderId = purchaseOrderId;
        this.receiptNumber = receiptNumber;
        this.receivedAt = receivedAt;
        this.status = status;
    }

    public UUID getPurchaseOrderId() { return purchaseOrderId; }
    public String getReceiptNumber() { return receiptNumber; }
    public Instant getReceivedAt() { return receivedAt; }
    public GoodsReceiptStatus getStatus() { return status; }
}
