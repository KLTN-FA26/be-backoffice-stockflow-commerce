package com.stockflow.warehouse.internal.entity;

import com.stockflow.warehouse.internal.domain.PutawayStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * JPA mapping of a putaway task (table {@code warehouse.putaway_task}). Not the domain model.
 *
 * <p>STARTER ENTITY. Created when goods are received and need moving to a storage location.
 * {@code goodsReceiptId} references procurement across schemas — a plain UUID, no FK.</p>
 */
@Entity
@Table(name = "putaway_task", schema = "warehouse")
public class PutawayTaskJpaEntity extends BaseEntity {

    /** Cross-schema reference to {@code procurement.goods_receipt}. Plain UUID by design. */
    @Column(name = "goods_receipt_id")
    private UUID goodsReceiptId;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** Same-schema reference to {@code warehouse.location}. */
    @Column(name = "target_location_id")
    private UUID targetLocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PutawayStatus status;

    protected PutawayTaskJpaEntity() {
    }

    public PutawayTaskJpaEntity(UUID id, UUID goodsReceiptId, String sku, int quantity,
                                UUID targetLocationId, PutawayStatus status) {
        super(id);
        this.goodsReceiptId = goodsReceiptId;
        this.sku = sku;
        this.quantity = quantity;
        this.targetLocationId = targetLocationId;
        this.status = status;
    }

    public UUID getGoodsReceiptId() { return goodsReceiptId; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public UUID getTargetLocationId() { return targetLocationId; }
    public PutawayStatus getStatus() { return status; }
}
