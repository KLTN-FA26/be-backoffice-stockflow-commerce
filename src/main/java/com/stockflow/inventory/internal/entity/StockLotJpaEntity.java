package com.stockflow.inventory.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code inventory.stock_lot} (SCRUM-142): a lot as received. Written once when a receipt opens it (or by the
 * database for stock that arrived without one, as UNTRACED) and never changed, so there are no setters.
 */
@Entity
@Table(name = "stock_lot", schema = "inventory")
public class StockLotJpaEntity extends BaseEntity {

    /** Where a lot's identity comes from; {@code ck_stock_lot_origin}. */
    public enum Origin { GOODS_RECEIPT, UNTRACED }

    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "lot_number", length = 64, updatable = false)
    private String lotNumber;

    @Column(name = "expiry_date", updatable = false)
    private LocalDate expiryDate;

    @Column(name = "received_at", updatable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_type", nullable = false, length = 16, updatable = false)
    private Origin origin;

    @Column(name = "goods_receipt_id", updatable = false)
    private UUID goodsReceiptId;

    @Column(name = "goods_receipt_number", length = 30, updatable = false)
    private String goodsReceiptNumber;

    @Column(name = "purchase_order_id", updatable = false)
    private UUID purchaseOrderId;

    @Column(name = "purchase_order_number", length = 30, updatable = false)
    private String purchaseOrderNumber;

    @Column(name = "supplier_id", updatable = false)
    private UUID supplierId;

    @Column(name = "supplier_name", length = 200, updatable = false)
    private String supplierName;

    @Column(name = "warehouse_id", updatable = false)
    private UUID warehouseId;

    protected StockLotJpaEntity() {
    }

    public StockLotJpaEntity(UUID id, String sku, String lotNumber, LocalDate expiryDate, Instant receivedAt,
                             UUID goodsReceiptId, String goodsReceiptNumber, UUID purchaseOrderId,
                             String purchaseOrderNumber, UUID supplierId, String supplierName, UUID warehouseId) {
        super(id);
        this.sku = sku;
        this.lotNumber = lotNumber;
        this.expiryDate = expiryDate;
        this.receivedAt = receivedAt;
        this.origin = Origin.GOODS_RECEIPT;
        this.goodsReceiptId = goodsReceiptId;
        this.goodsReceiptNumber = goodsReceiptNumber;
        this.purchaseOrderId = purchaseOrderId;
        this.purchaseOrderNumber = purchaseOrderNumber;
        this.supplierId = supplierId;
        this.supplierName = supplierName;
        this.warehouseId = warehouseId;
    }

    public String getSku() { return sku; }
    public String getLotNumber() { return lotNumber; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public Instant getReceivedAt() { return receivedAt; }
    public Origin getOrigin() { return origin; }
    public UUID getGoodsReceiptId() { return goodsReceiptId; }
    public String getGoodsReceiptNumber() { return goodsReceiptNumber; }
    public UUID getPurchaseOrderId() { return purchaseOrderId; }
    public String getPurchaseOrderNumber() { return purchaseOrderNumber; }
    public UUID getSupplierId() { return supplierId; }
    public String getSupplierName() { return supplierName; }
    public UUID getWarehouseId() { return warehouseId; }
}
