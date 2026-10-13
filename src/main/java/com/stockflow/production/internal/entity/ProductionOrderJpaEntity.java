package com.stockflow.production.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.production.internal.domain.ProductionOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code production.production_order} (V20261010000200), as far as creating and reading an order needs.
 *
 * <p>The workflow columns — status, hold, prepress, quantities, completion, cancellation — are written
 * through {@link #applyWorkflow} (SCRUM-396..401); a new row takes the table's defaults for them
 * ({@code insertable = false}: zero quantities). The subcontracting columns (SCRUM-441) are written when a
 * child is created, and when a whole order is converted (BR-PRD-12); {@code planned_quantity} changes on the
 * parent of a split and on a child whose shortfall went to the print shop.</p>
 */
@Entity
@Table(name = "production_order", schema = "production")
public class ProductionOrderJpaEntity extends BaseEntity {

    @Column(name = "order_number", nullable = false, length = 30, updatable = false)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16, updatable = false)
    private ProductionOrder.Type type;

    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "subcontract_method", length = 20)
    private String subcontractMethod;

    @Column(name = "subcontractor_id")
    private UUID subcontractorId;

    @Column(name = "subcontract_unit_price", precision = 19, scale = 4)
    private java.math.BigDecimal subcontractUnitPrice;

    @Column(name = "subcontract_currency", length = 3)
    private String subcontractCurrency;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ProductionOrder.Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_before_hold", length = 32)
    private ProductionOrder.Status statusBeforeHold;

    @Enumerated(EnumType.STRING)
    @Column(name = "hold_reason", length = 32)
    private ProductionOrder.HoldReason holdReason;

    @Column(name = "hold_note", length = 1000)
    private String holdNote;

    @Column(name = "prepress_checked_by")
    private UUID prepressCheckedBy;

    @Column(name = "sample_request_id", updatable = false)
    private UUID sampleRequestId;

    @Column(name = "sales_order_id", updatable = false)
    private UUID salesOrderId;

    @Column(name = "sales_order_line_id", updatable = false)
    private UUID salesOrderLineId;

    @Column(name = "origin", length = 16, updatable = false)
    private String origin;

    @Column(name = "parent_id", updatable = false)
    private UUID parentId;

    @Column(name = "approved_sample_id", updatable = false)
    private UUID approvedSampleId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "blank_sku", nullable = false, length = 64, updatable = false)
    private String blankSku;

    @Column(name = "design_snapshot_id", nullable = false, updatable = false)
    private UUID designSnapshotId;

    @Column(name = "design_checksum", nullable = false, length = 128, updatable = false)
    private String designChecksum;

    @Column(name = "due_date", updatable = false)
    private LocalDate dueDate;

    @Column(name = "planned_quantity", nullable = false)
    private int plannedQuantity;

    @Column(name = "issued_quantity", insertable = false)
    private int issuedQuantity;

    @Column(name = "printed_quantity", insertable = false)
    private int printedQuantity;

    @Column(name = "good_quantity", insertable = false)
    private int goodQuantity;

    @Column(name = "scrap_quantity", insertable = false)
    private int scrapQuantity;

    @Column(name = "prepress_checked_at")
    private Instant prepressCheckedAt;

    @Column(name = "completed_at", insertable = false)
    private Instant completedAt;

    @Column(name = "cancelled_at", insertable = false)
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", insertable = false)
    private String cancellationReason;

    protected ProductionOrderJpaEntity() {
    }

    public ProductionOrderJpaEntity(ProductionOrder order) {
        super(order.id());
        this.orderNumber = order.orderNumber();
        this.type = order.type();
        this.status = order.status();
        this.sampleRequestId = order.sampleRequestId();
        this.salesOrderId = order.salesOrderId();
        this.salesOrderLineId = order.salesOrderLineId();
        this.approvedSampleId = order.approvedSampleId();
        this.warehouseId = order.work().warehouseId();
        this.blankSku = order.work().blankSku().code();
        this.designSnapshotId = order.work().designSnapshotId();
        this.designChecksum = order.work().designChecksum();
        this.dueDate = order.work().dueDate();
        this.plannedQuantity = order.work().plannedQuantity();
        this.mode = order.mode().name();
        this.parentId = order.parentId();
        this.origin = order.origin() == null ? null : order.origin().name();
        if (order.subcontract() != null) {
            this.subcontractMethod = order.subcontract().method().name();
            this.subcontractorId = order.subcontract().subcontractorId();
            this.subcontractUnitPrice = order.subcontract().unitPrice();
            this.subcontractCurrency = order.subcontract().currency();
            this.purchaseOrderId = order.subcontract().purchaseOrderId();
        }
    }

    /** The workflow columns the aggregate owns (SCRUM-396 onwards). */
    public void applyWorkflow(ProductionOrder order) {
        this.status = order.status();
        this.statusBeforeHold = order.statusBeforeHold();
        this.holdReason = order.holdReason();
        this.holdNote = order.holdNote();
        this.prepressCheckedBy = order.prepressCheckedBy();
        this.prepressCheckedAt = order.prepressCheckedAt();
        this.plannedQuantity = order.work().plannedQuantity();
        // A whole order converted to subcontracting (BR-PRD-12) changes its own mode and terms.
        this.mode = order.mode().name();
        ProductionOrder.Subcontract terms = order.subcontract();
        this.subcontractMethod = terms == null ? null : terms.method().name();
        this.subcontractorId = terms == null ? null : terms.subcontractorId();
        this.subcontractUnitPrice = terms == null ? null : terms.unitPrice();
        this.subcontractCurrency = terms == null ? null : terms.currency();
        this.purchaseOrderId = terms == null ? null : terms.purchaseOrderId();
        this.issuedQuantity = order.issued();
        this.printedQuantity = order.printed();
        this.goodQuantity = order.good();
        this.scrapQuantity = order.scrap();
        this.completedAt = order.completedAt();
        this.cancelledAt = order.cancelledAt();
        this.cancellationReason = order.cancellationReason();
    }

    public String getOrderNumber() { return orderNumber; }
    public ProductionOrder.Type getType() { return type; }
    public String getMode() { return mode; }
    public ProductionOrder.Status getStatus() { return status; }
    public ProductionOrder.HoldReason getHoldReason() { return holdReason; }
    public ProductionOrder.Status getStatusBeforeHold() { return statusBeforeHold; }
    public String getHoldNote() { return holdNote; }
    public UUID getPrepressCheckedBy() { return prepressCheckedBy; }
    public UUID getSampleRequestId() { return sampleRequestId; }
    public UUID getSalesOrderId() { return salesOrderId; }
    public UUID getSalesOrderLineId() { return salesOrderLineId; }
    public String getOrigin() { return origin; }
    public UUID getParentId() { return parentId; }
    public String getSubcontractMethod() { return subcontractMethod; }
    public UUID getSubcontractorId() { return subcontractorId; }
    public java.math.BigDecimal getSubcontractUnitPrice() { return subcontractUnitPrice; }
    public String getSubcontractCurrency() { return subcontractCurrency; }
    public UUID getPurchaseOrderId() { return purchaseOrderId; }
    public UUID getApprovedSampleId() { return approvedSampleId; }
    public UUID getWarehouseId() { return warehouseId; }
    public String getBlankSku() { return blankSku; }
    public UUID getDesignSnapshotId() { return designSnapshotId; }
    public String getDesignChecksum() { return designChecksum; }
    public LocalDate getDueDate() { return dueDate; }
    public int getPlannedQuantity() { return plannedQuantity; }
    public int getIssuedQuantity() { return issuedQuantity; }
    public int getPrintedQuantity() { return printedQuantity; }
    public int getGoodQuantity() { return goodQuantity; }
    public int getScrapQuantity() { return scrapQuantity; }
    public Instant getPrepressCheckedAt() { return prepressCheckedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getCancellationReason() { return cancellationReason; }
}
