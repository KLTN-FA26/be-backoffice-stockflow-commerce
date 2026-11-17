package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code procurement.goods_receipt_return_cartons} (V20261112000300): a carton scanned on a return receipt, named as it
 * was shipped. Only its condition changes, and only while the receipt is a draft ({@code check_return_receipt_carton}).
 */
@Entity
@Table(name = "goods_receipt_return_cartons", schema = "procurement")
public class ReturnReceiptCartonJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false, updatable = false)
    private ReturnReceiptJpaEntity receipt;

    @Column(name = "stock_package_id", nullable = false, updatable = false)
    private UUID stockPackageId;

    @Column(name = "lpn", nullable = false, length = 30, updatable = false)
    private String lpn;

    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "lot_number", length = 64, updatable = false)
    private String lotNumber;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    @Column(name = "condition_note", length = 500)
    private String conditionNote;

    @Column(name = "scanned_by", nullable = false, updatable = false)
    private UUID scannedBy;

    protected ReturnReceiptCartonJpaEntity() {
    }

    public ReturnReceiptCartonJpaEntity(UUID id, UUID stockPackageId, String lpn, String sku, String lotNumber,
                                        int quantity, String conditionNote, UUID scannedBy) {
        super(id);
        this.stockPackageId = stockPackageId;
        this.lpn = lpn;
        this.sku = sku;
        this.lotNumber = lotNumber;
        this.quantity = quantity;
        this.conditionNote = conditionNote;
        this.scannedBy = scannedBy;
    }

    void attachTo(ReturnReceiptJpaEntity owner) {
        this.receipt = owner;
    }

    public void noteCondition(String condition) {
        this.conditionNote = condition;
    }

    public UUID getStockPackageId() { return stockPackageId; }
    public String getLpn() { return lpn; }
    public String getSku() { return sku; }
    public String getLotNumber() { return lotNumber; }
    public int getQuantity() { return quantity; }
    public String getConditionNote() { return conditionNote; }
    public UUID getScannedBy() { return scannedBy; }
    /** When it was scanned: the row's creation time. */
    public Instant getScannedAt() { return getCreatedAt(); }
}
