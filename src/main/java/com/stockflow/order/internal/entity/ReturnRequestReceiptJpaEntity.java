package com.stockflow.order.internal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code ordering.return_request_receipt}: a confirmed return receipt that brought a parcel of a return's goods back
 * (WF-05 TH-3), keyed by the receipt - one receipt, one parcel of one return. Append-only.
 */
@Entity
@Table(name = "return_request_receipt", schema = "ordering")
public class ReturnRequestReceiptJpaEntity {

    @Id
    @Column(name = "goods_receipt_id", nullable = false, updatable = false)
    private UUID goodsReceiptId;

    @Column(name = "return_request_id", nullable = false, updatable = false)
    private UUID returnRequestId;

    @Column(name = "parcel", nullable = false, updatable = false)
    private int parcel;

    @Column(name = "receipt_number", nullable = false, length = 30, updatable = false)
    private String receiptNumber;

    @Column(name = "receiving_location_code", nullable = false, length = 64, updatable = false)
    private String receivingLocationCode;

    @Column(name = "received_by", nullable = false, updatable = false)
    private UUID receivedBy;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected ReturnRequestReceiptJpaEntity() {
    }

    public ReturnRequestReceiptJpaEntity(UUID goodsReceiptId, UUID returnRequestId, int parcel, String receiptNumber,
                                         String receivingLocationCode, UUID receivedBy, Instant receivedAt) {
        this.goodsReceiptId = goodsReceiptId;
        this.returnRequestId = returnRequestId;
        this.parcel = parcel;
        this.receiptNumber = receiptNumber;
        this.receivingLocationCode = receivingLocationCode;
        this.receivedBy = receivedBy;
        this.receivedAt = receivedAt;
    }

    public UUID getGoodsReceiptId() { return goodsReceiptId; }
    public UUID getReturnRequestId() { return returnRequestId; }
    public int getParcel() { return parcel; }
    public String getReceiptNumber() { return receiptNumber; }
    public String getReceivingLocationCode() { return receivingLocationCode; }
    public UUID getReceivedBy() { return receivedBy; }
    public Instant getReceivedAt() { return receivedAt; }
}
