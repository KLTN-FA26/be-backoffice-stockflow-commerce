package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.procurement.internal.domain.GoodsReceiptStatus;
import com.stockflow.procurement.internal.domain.ReceiptApproval;
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

    @Column(name = "po_id", updatable = false)
    private UUID purchaseOrderId;

    @Column(name = "po_revision_id", updatable = false)
    private UUID purchaseOrderRevisionId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    /** SCRUM-478: the supplier and the reason of a receipt without a PO (both null on one against a PO). */
    @Column(name = "supplier_id", updatable = false)
    private UUID supplierId;

    @Column(name = "blind_reason", length = 500, updatable = false)
    private String blindReason;

    /** A PO attached afterwards to a receipt without one: a reference only. */
    @Column(name = "referenced_po_id")
    private UUID referencedPurchaseOrderId;

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

    /** SCRUM-477: the manager's exception approval (BR-RCP-001); all null for a receipt that never needed one. */
    @Column(name = "approval_requested_at")
    private Instant approvalRequestedAt;

    @Column(name = "approval_requested_by")
    private UUID approvalRequestedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_decision", length = 16)
    private ReceiptApproval.Decision approvalDecision;

    @Column(name = "approval_decided_at")
    private Instant approvalDecidedAt;

    @Column(name = "approval_decided_by")
    private UUID approvalDecidedBy;

    @Column(name = "approval_reason", length = 500)
    private String approvalReason;

    @Column(name = "delivery_note", length = 100, updatable = false)
    private String deliveryNote;

    @Column(name = "note", updatable = false)
    private String note;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<GoodsReceiptLineJpaEntity> lines = new ArrayList<>();

    protected GoodsReceiptJpaEntity() {
    }

    public GoodsReceiptJpaEntity(UUID id, String receiptNumber, UUID purchaseOrderId, UUID purchaseOrderRevisionId,
                                 UUID warehouseId, UUID supplierId, String blindReason, Instant receivedAt,
                                 UUID receivedBy, String deliveryNote, String note) {
        super(id);
        this.supplierId = supplierId;
        this.blindReason = blindReason;
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

    public void apply(GoodsReceiptStatus status, Instant confirmedAt, UUID confirmedBy, Instant closedAt,
                      ReceiptApproval approval, UUID referencedPurchaseOrderId) {
        this.referencedPurchaseOrderId = referencedPurchaseOrderId;
        this.status = status;
        this.confirmedAt = confirmedAt;
        this.confirmedBy = confirmedBy;
        this.closedAt = closedAt;
        this.approvalRequestedAt = approval == null ? null : approval.requestedAt();
        this.approvalRequestedBy = approval == null ? null : approval.requestedBy();
        this.approvalDecision = approval == null ? null : approval.decision();
        this.approvalDecidedAt = approval == null ? null : approval.decidedAt();
        this.approvalDecidedBy = approval == null ? null : approval.decidedBy();
        this.approvalReason = approval == null ? null : approval.reason();
    }

    /** The approval as stored; null when the receipt never asked for one. */
    public ReceiptApproval approval() {
        return approvalRequestedAt == null ? null : new ReceiptApproval(approvalRequestedAt, approvalRequestedBy,
                approvalDecision, approvalDecidedAt, approvalDecidedBy, approvalReason);
    }

    public void addLine(GoodsReceiptLineJpaEntity line) {
        line.attachTo(this);
        lines.add(line);
    }

    public String getReceiptNumber() { return receiptNumber; }
    public UUID getPurchaseOrderId() { return purchaseOrderId; }
    public UUID getPurchaseOrderRevisionId() { return purchaseOrderRevisionId; }
    public UUID getWarehouseId() { return warehouseId; }
    public UUID getSupplierId() { return supplierId; }
    public String getBlindReason() { return blindReason; }
    public UUID getReferencedPurchaseOrderId() { return referencedPurchaseOrderId; }
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
