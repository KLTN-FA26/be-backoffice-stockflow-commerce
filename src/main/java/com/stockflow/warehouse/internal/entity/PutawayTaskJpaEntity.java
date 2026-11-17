package com.stockflow.warehouse.internal.entity;

import com.stockflow.warehouse.api.PutawaySource;
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
 * <p>Created when a carton needs moving to a bin. A task has one {@link PutawaySource}
 * ({@code ck_putaway_task_source}): a goods receipt line (with its receipt), or a carton released from quarantine,
 * which names no line (team plan 4D-1). The receipt references are foreign keys to
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

    @Column(name = "sku", nullable = false, length = 64)
    private String sku;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** The carton this task puts away ({@code inventory.stock_package}); null for loose stock. */
    @Column(name = "stock_package_id")
    private UUID stockPackageId;

    /** A {@code warehouse.storage_location}: a bin or a storage area. */
    @Column(name = "target_location_id")
    private UUID targetLocationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PutawayStatus status;

    /** Fixed once set ({@code tg_putaway_task_source_fixed}). */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 32, updatable = false)
    private PutawaySource source;

    protected PutawayTaskJpaEntity() {
    }

    /** A task for one carton: of a counted goods receipt line, or released from quarantine. */
    public PutawayTaskJpaEntity(UUID id, UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, int quantity,
                                UUID stockPackageId, UUID targetLocationId, PutawayStatus status,
                                PutawaySource source) {
        super(id);
        this.goodsReceiptId = goodsReceiptId;
        this.goodsReceiptLineId = goodsReceiptLineId;
        this.sku = sku;
        this.quantity = quantity;
        this.stockPackageId = stockPackageId;
        this.targetLocationId = targetLocationId;
        this.status = status;
        this.source = source;
    }

    /** The only columns a task changes after it is opened. */
    public void apply(int quantity, UUID targetLocationId, PutawayStatus status) {
        this.quantity = quantity;
        this.targetLocationId = targetLocationId;
        this.status = status;
    }

    public UUID getGoodsReceiptId() { return goodsReceiptId; }
    public UUID getGoodsReceiptLineId() { return goodsReceiptLineId; }
    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
    public UUID getStockPackageId() { return stockPackageId; }
    public UUID getTargetLocationId() { return targetLocationId; }
    public PutawayStatus getStatus() { return status; }
    public PutawaySource getSource() { return source; }
}
