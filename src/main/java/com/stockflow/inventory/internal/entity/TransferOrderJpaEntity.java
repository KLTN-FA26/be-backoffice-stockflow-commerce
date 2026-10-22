package com.stockflow.inventory.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.inventory.internal.domain.TransferReason;
import com.stockflow.inventory.internal.domain.TransferStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code inventory.transfer_order}. Carrier, tracking number and shipping cost are left unmapped:
 * since 2026-10-06 the journey is not tracked (SCRUM-327), and the columns stay null. Warehouses,
 * reason and date change only while DRAFT ({@link #reviseDraft}, {@code tg_transfer_order_draft_header}).
 */
@Entity
@Table(name = "transfer_order", schema = "inventory")
public class TransferOrderJpaEntity extends BaseEntity {

    @Column(name = "transfer_number", nullable = false, length = 30, updatable = false)
    private String transferNumber;

    @Column(name = "from_warehouse_id", nullable = false)
    private UUID fromWarehouseId;

    @Column(name = "to_warehouse_id", nullable = false)
    private UUID toWarehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransferStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", length = 16)
    private TransferReason reason;

    @Column(name = "expected_date")
    private LocalDate expectedDate;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "dispatched_by")
    private UUID dispatchedBy;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    /** SCRUM-287 PR T (LP2-8): how it travels; its packing completed; its goods first left on a vehicle. */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_mode", nullable = false, length = 16)
    private com.stockflow.inventory.api.TransferDeliveryMode deliveryMode =
            com.stockflow.inventory.api.TransferDeliveryMode.FULL_TRUCKLOAD;

    @Column(name = "packed_at")
    private Instant packedAt;

    @Column(name = "goods_left_at")
    private Instant goodsLeftAt;

    @OneToMany(mappedBy = "transferOrder", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNo")
    private List<TransferOrderLineJpaEntity> lines = new ArrayList<>();

    protected TransferOrderJpaEntity() {
    }

    public TransferOrderJpaEntity(UUID id, String transferNumber, UUID fromWarehouseId, UUID toWarehouseId,
                                  TransferReason reason, LocalDate expectedDate) {
        super(id);
        this.transferNumber = transferNumber;
        this.fromWarehouseId = fromWarehouseId;
        this.toWarehouseId = toWarehouseId;
        this.reason = reason;
        this.expectedDate = expectedDate;
        this.status = TransferStatus.DRAFT;
    }

    public void apply(TransferStatus status, UUID submittedBy, Instant submittedAt, UUID approvedBy, Instant approvedAt,
                      UUID dispatchedBy, Instant dispatchedAt, String cancelReason) {
        this.status = status;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.dispatchedBy = dispatchedBy;
        this.dispatchedAt = dispatchedAt;
        this.cancelReason = cancelReason;
    }

    public void applyFulfillment(com.stockflow.inventory.api.TransferDeliveryMode deliveryMode, Instant packedAt,
                                 Instant goodsLeftAt) {
        this.deliveryMode = deliveryMode;
        this.packedAt = packedAt;
        this.goodsLeftAt = goodsLeftAt;
    }

    /** A DRAFT's new header (SCRUM-462); the lines are replaced by the repository. */
    public void reviseDraft(UUID fromWarehouseId, UUID toWarehouseId, TransferReason reason, LocalDate expectedDate) {
        if (status != TransferStatus.DRAFT) {
            throw new IllegalStateException("Transfer " + transferNumber + " is " + status + ", not DRAFT");
        }
        this.fromWarehouseId = fromWarehouseId;
        this.toWarehouseId = toWarehouseId;
        this.reason = reason;
        this.expectedDate = expectedDate;
    }

    public void addLine(TransferOrderLineJpaEntity line) {
        line.attachTo(this);
        lines.add(line);
    }

    public String getTransferNumber() { return transferNumber; }
    public UUID getFromWarehouseId() { return fromWarehouseId; }
    public UUID getToWarehouseId() { return toWarehouseId; }
    public TransferStatus getStatus() { return status; }
    public TransferReason getReason() { return reason; }
    public LocalDate getExpectedDate() { return expectedDate; }
    public UUID getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public UUID getDispatchedBy() { return dispatchedBy; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public String getCancelReason() { return cancelReason; }
    public com.stockflow.inventory.api.TransferDeliveryMode getDeliveryMode() { return deliveryMode; }
    public Instant getPackedAt() { return packedAt; }
    public Instant getGoodsLeftAt() { return goodsLeftAt; }
    public List<TransferOrderLineJpaEntity> getLines() { return lines; }
}
