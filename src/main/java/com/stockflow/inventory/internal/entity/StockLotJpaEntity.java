package com.stockflow.inventory.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code inventory.stock_lot} (SCRUM-142): a lot as received. Written once when a receipt opens it (or by the
 * database for stock that arrived without one, as UNTRACED) and never changed, so there are no setters. A lot of goods
 * received against a production order (4A-5) is the production order's, opened by its first receipt.
 */
@Entity
@Table(name = "stock_lot", schema = "inventory")
public class StockLotJpaEntity extends BaseEntity {

    /** Where a lot's identity comes from; {@code ck_stock_lot_origin}. */
    public enum Origin { GOODS_RECEIPT, PRODUCTION_ORDER, UNTRACED }

    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "lot_number", length = 64, updatable = false)
    private String lotNumber;

    @Column(name = "received_at", updatable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_type", nullable = false, length = 16, updatable = false)
    private Origin origin;

    @Column(name = "goods_receipt_id", updatable = false)
    private UUID goodsReceiptId;

    @Column(name = "goods_receipt_number", length = 30, updatable = false)
    private String goodsReceiptNumber;

    @Column(name = "warehouse_id", updatable = false)
    private UUID warehouseId;

    @Column(name = "production_order_id", updatable = false)
    private UUID productionOrderId;

    @Column(name = "production_order_code", length = 32, updatable = false)
    private String productionOrderCode;

    protected StockLotJpaEntity() {
    }

    public StockLotJpaEntity(UUID id, String sku, String lotNumber, Instant receivedAt,
                             UUID goodsReceiptId, String goodsReceiptNumber, UUID warehouseId) {
        super(id);
        this.sku = sku;
        this.lotNumber = lotNumber;
        this.receivedAt = receivedAt;
        this.origin = Origin.GOODS_RECEIPT;
        this.goodsReceiptId = goodsReceiptId;
        this.goodsReceiptNumber = goodsReceiptNumber;
        this.warehouseId = warehouseId;
    }

    /**
     * The lot of a production order's SKU (4A-5, kltn-docs 03 BR-03): numbered with the production order's code,
     * received when its first goods were, at the warehouse the production order is received at.
     */
    public static StockLotJpaEntity ofProductionOrder(UUID id, String sku, Instant receivedAt, UUID warehouseId,
                                                      UUID productionOrderId, String productionOrderCode) {
        StockLotJpaEntity lot = new StockLotJpaEntity(id, sku, productionOrderCode, receivedAt, null, null,
                warehouseId);
        lot.origin = Origin.PRODUCTION_ORDER;
        lot.productionOrderId = productionOrderId;
        lot.productionOrderCode = productionOrderCode;
        return lot;
    }

    public String getSku() { return sku; }
    public String getLotNumber() { return lotNumber; }
    public Instant getReceivedAt() { return receivedAt; }
    public Origin getOrigin() { return origin; }
    public UUID getGoodsReceiptId() { return goodsReceiptId; }
    public String getGoodsReceiptNumber() { return goodsReceiptNumber; }
    public UUID getWarehouseId() { return warehouseId; }
    public UUID getProductionOrderId() { return productionOrderId; }
    public String getProductionOrderCode() { return productionOrderCode; }
}
