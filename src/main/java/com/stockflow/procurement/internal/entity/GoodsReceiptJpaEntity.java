package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code procurement.goods_receipts}. Not the domain model; see {@code GoodsReceipt}.
 */
@Entity
@Table(name = "goods_receipts", schema = "procurement")
public class GoodsReceiptJpaEntity extends BaseEntity {

    @Column(name = "receipt_number", nullable = false, length = 30, updatable = false)
    private String receiptNumber;

    @Column(name = "po_id", nullable = false, updatable = false)
    private UUID purchaseOrderId;

    @Column(name = "po_revision_id", nullable = false, updatable = false)
    private UUID purchaseOrderRevisionId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private GoodsReceiptStatus status;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "received_by", nullable = false, updatable = false)
    private UUID receivedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "delivery_note", length = 100, updatable = false)
    private String deliveryNote;

    @Column(name = "note", updatable = false)
    private String note;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<GoodsReceiptLineJpaEntity> lines = new ArrayList<>();

    protected GoodsReceiptJpaEntity() {
    }

    public GoodsReceiptJpaEntity(UUID id, String receiptNumber, UUID purchaseOrderId, UUID purchaseOrderRevisionId,
                                 UUID warehouseId, Instant receivedAt, UUID receivedBy, String deliveryNote,
                                 String note) {
        super(id);
        this.receiptNumber = receiptNumber;
        this.purchaseOrderId = purchaseOrderId;
        this.purchaseOrderRevisionId = purchaseOrderRevisionId;
        this.warehouseId = warehouseId;
        this.receivedAt = receivedAt;
        this.receivedBy = receivedBy;
        this.deliveryNote = deliveryNote;
        this.note = note;
        this.status = GoodsReceiptStatus.DRAFT;
    }

    public void apply(GoodsReceiptStatus status, Instant confirmedAt, UUID confirmedBy, Instant closedAt) {
        this.status = status;
        this.confirmedAt = confirmedAt;
        this.confirmedBy = confirmedBy;
        this.closedAt = closedAt;
    }

    public void addLine(GoodsReceiptLineJpaEntity line) {
        line.attachTo(this);
        lines.add(line);
    }

    public String getReceiptNumber() { return receiptNumber; }
    public UUID getPurchaseOrderId() { return purchaseOrderId; }
    public UUID getPurchaseOrderRevisionId() { return purchaseOrderRevisionId; }
    public UUID getWarehouseId() { return warehouseId; }
    public GoodsReceiptStatus getStatus() { return status; }
    public Instant getReceivedAt() { return receivedAt; }
    public UUID getReceivedBy() { return receivedBy; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public UUID getConfirmedBy() { return confirmedBy; }
    public Instant getClosedAt() { return closedAt; }
    public String getDeliveryNote() { return deliveryNote; }
    public String getNote() { return note; }
    public List<GoodsReceiptLineJpaEntity> getLines() { return lines; }
}
