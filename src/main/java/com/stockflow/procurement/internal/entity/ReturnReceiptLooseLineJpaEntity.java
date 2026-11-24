package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * {@code procurement.goods_receipt_return_loose_lines} (V20261201000310, WF-05 TH-4): units that came back on a return
 * receipt with no carton label. Only the count changes, and only while the receipt is a draft
 * ({@code check_return_receipt_loose_line}).
 */
@Entity
@Table(name = "goods_receipt_return_loose_lines", schema = "procurement")
public class ReturnReceiptLooseLineJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false, updatable = false)
    private ReturnReceiptJpaEntity receipt;

    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "lot_number", length = 64, updatable = false)
    private String lotNumber;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /** Whose count it is now: a recount is its recorder's (WF-05 Q-18). */
    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    /** The photo of the units, one of the receipt's own (WF-05 Q-18); null only on lines recorded before. */
    @Column(name = "photo_id")
    private UUID photoId;

    protected ReturnReceiptLooseLineJpaEntity() {
    }

    public ReturnReceiptLooseLineJpaEntity(UUID id, String sku, String lotNumber, int quantity, UUID recordedBy,
                                           UUID photoId) {
        super(id);
        this.sku = sku;
        this.lotNumber = lotNumber;
        this.quantity = quantity;
        this.recordedBy = recordedBy;
        this.photoId = photoId;
    }

    void attachTo(ReturnReceiptJpaEntity owner) {
        this.receipt = owner;
    }

    public void recount(int units, UUID by, UUID photo) {
        this.quantity = units;
        this.recordedBy = by;
        this.photoId = photo;
    }

    public String getSku() { return sku; }
    public String getLotNumber() { return lotNumber; }
    public int getQuantity() { return quantity; }
    public UUID getRecordedBy() { return recordedBy; }
    public UUID getPhotoId() { return photoId; }
}
