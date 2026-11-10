package com.stockflow.order.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code ordering.production_order} (4A-2). Its lines ({@code production_order_line}) and its history
 * ({@code production_order_status_history}) are written by the adapter beside it: plain rows with no life of their own.
 */
@Entity
@Table(name = "production_order", schema = "ordering")
public class ProductionOrderJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, updatable = false, length = 32)
    private String code;

    @Column(name = "sales_order_id", nullable = false, updatable = false)
    private UUID salesOrderId;

    @Column(name = "delivery_batch_id", nullable = false, updatable = false)
    private UUID deliveryBatchId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "producer_id", nullable = false)
    private UUID producerId;

    @Column(name = "receiving_warehouse_id", nullable = false)
    private UUID receivingWarehouseId;

    @Column(name = "need_by_date", nullable = false)
    private LocalDate needByDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private ProductionOrderStatus status;

    @Column(name = "sample_approval_required", nullable = false)
    private boolean sampleApprovalRequired;

    @Column(name = "sample_required_by_default", nullable = false, updatable = false)
    private boolean sampleRequiredByDefault;

    @Column(name = "sample_waiver_reason", length = 500)
    private String sampleWaiverReason;

    @Column(name = "unit_price", precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "drawn_up_by", nullable = false, updatable = false)
    private UUID drawnUpBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "returned_reason", length = 500)
    private String returnedReason;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "production_started_at")
    private Instant productionStartedAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closing_reason", length = 500)
    private String closingReason;

    protected ProductionOrderJpaEntity() {
    }

    public ProductionOrderJpaEntity(UUID id, String code, UUID salesOrderId, UUID deliveryBatchId, UUID customerId,
                                    boolean sampleRequiredByDefault, String currency, UUID drawnUpBy) {
        super(id);
        this.code = code;
        this.salesOrderId = salesOrderId;
        this.deliveryBatchId = deliveryBatchId;
        this.customerId = customerId;
        this.sampleRequiredByDefault = sampleRequiredByDefault;
        this.currency = currency;
        this.drawnUpBy = drawnUpBy;
    }

    /** Everything that moves after it was drawn up. */
    public void apply(UUID producerId, UUID receivingWarehouseId, LocalDate needByDate, ProductionOrderStatus status,
                      boolean sampleApprovalRequired, String sampleWaiverReason, BigDecimal unitPrice, String note,
                      Instant submittedAt, UUID approvedBy, Instant approvedAt, String returnedReason, Instant sentAt,
                      Instant productionStartedAt, UUID cancelledBy, Instant cancelledAt, String cancellationReason,
                      UUID closedBy, Instant closedAt, String closingReason) {
        this.producerId = producerId;
        this.receivingWarehouseId = receivingWarehouseId;
        this.needByDate = needByDate;
        this.status = status;
        this.sampleApprovalRequired = sampleApprovalRequired;
        this.sampleWaiverReason = sampleWaiverReason;
        this.unitPrice = unitPrice;
        this.note = note;
        this.submittedAt = submittedAt;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.returnedReason = returnedReason;
        this.sentAt = sentAt;
        this.productionStartedAt = productionStartedAt;
        this.cancelledBy = cancelledBy;
        this.cancelledAt = cancelledAt;
        this.cancellationReason = cancellationReason;
        this.closedBy = closedBy;
        this.closedAt = closedAt;
        this.closingReason = closingReason;
    }

    public String getCode() { return code; }
    public UUID getSalesOrderId() { return salesOrderId; }
    public UUID getDeliveryBatchId() { return deliveryBatchId; }
    public UUID getCustomerId() { return customerId; }
    public UUID getProducerId() { return producerId; }
    public UUID getReceivingWarehouseId() { return receivingWarehouseId; }
    public LocalDate getNeedByDate() { return needByDate; }
    public ProductionOrderStatus getStatus() { return status; }
    public boolean isSampleApprovalRequired() { return sampleApprovalRequired; }
    public boolean isSampleRequiredByDefault() { return sampleRequiredByDefault; }
    public String getSampleWaiverReason() { return sampleWaiverReason; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public String getCurrency() { return currency; }
    public String getNote() { return note; }
    public UUID getDrawnUpBy() { return drawnUpBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getReturnedReason() { return returnedReason; }
    public Instant getSentAt() { return sentAt; }
    public Instant getProductionStartedAt() { return productionStartedAt; }
    public UUID getCancelledBy() { return cancelledBy; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getCancellationReason() { return cancellationReason; }
    public UUID getClosedBy() { return closedBy; }
    public Instant getClosedAt() { return closedAt; }
    public String getClosingReason() { return closingReason; }
}
