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
 * <p>STARTER ENTITY. Created when goods arrive and need moving to a storage location. A task has
 * exactly one source ({@code ck_putaway_task_source}): a goods receipt line (with its receipt), a
 * transfer order line or a return line. The receipt references are foreign keys to
 * {@code procurement.goods_receipts}/{@code goods_receipt_lines} since contract C4, the target one to
 * {@code warehouse.storage_location} (a bin or a storage area) since C2.</p>
 */
@Entity
@Table(name = "putaway_task", schema = "warehouse")
public class PutawayTaskJpaEntity extends BaseEntity {

    @Column(name = "goods_receipt_id")
    private UUID goodsReceiptId;

    @Column(name = "goods_receipt_line_id")
    private UUID goodsReceiptLineId;

    @Column(name = "transfer_order_line_id")
    private UUID transferOrderLineId;

    @Column(name = "return_request_line_id")
    private UUID returnRequestLineId;

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** A {@code warehouse.storage_location}: a bin or a storage area. */
    @Column(name = "target_location_id")
    private UUID targetLocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PutawayStatus status;

    protected PutawayTaskJpaEntity() {
    }

    /** A task for one counted goods receipt line. */
    public PutawayTaskJpaEntity(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, int quantity,
                                UUID targetLocationId, PutawayStatus status) {
        super(id);
        this.goodsReceiptId = goodsReceiptId;
        this.goodsReceiptLineId = goodsReceiptLineId;
        this.sku = sku;
        this.quantity = quantity;
        this.targetLocationId = targetLocationId;
        this.status = status;
    }

    public UUID getGoodsReceiptId() { return goodsReceiptId; }
    public UUID getGoodsReceiptLineId() { return goodsReceiptLineId; }
    public UUID getTransferOrderLineId() { return transferOrderLineId; }
    public UUID getReturnRequestLineId() { return returnRequestLineId; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public UUID getTargetLocationId() { return targetLocationId; }
    public PutawayStatus getStatus() { return status; }
}
