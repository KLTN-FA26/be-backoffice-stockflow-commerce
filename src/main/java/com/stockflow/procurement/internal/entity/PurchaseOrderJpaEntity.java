package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.procurement.internal.domain.CloseKind;
import com.stockflow.procurement.internal.domain.PurchaseOrder;
import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.SupplierConfirmationStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA mapping of a purchase order (table {@code procurement.purchase_orders}). Not the domain model.
 *
 * <p>Number, supplier, warehouse, type and production order are fixed at creation — the
 * {@code tg_purchase_orders_immutable} triggers state it again, so they are
 * {@code updatable = false} here. Receiving changes {@code status} with native SQL from the goods
 * receipt; the {@code @Version} column makes a concurrent write through this entity fail instead of
 * putting the old status back. Free-text payment terms, incoterms, discounts, shipping fee and the
 * replenishment link are not edited by any screen yet and are not mapped, so nothing overwrites them.</p>
 */
@Entity
@Table(name = "purchase_orders", schema = "procurement")
public class PurchaseOrderJpaEntity extends BaseEntity {

    @Column(name = "po_number", nullable = false, length = 30, updatable = false)
    private String poNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "po_type", nullable = false, length = 16, updatable = false)
    private PurchaseOrder.Type type;

    @Column(name = "production_order_id", updatable = false)
    private UUID productionOrderId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PurchaseOrderStatus status;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "order_date", nullable = false, updatable = false)
    private LocalDate orderDate;

    @Column(name = "expected_date")
    private LocalDate expectedAt;

    @Column(name = "subtotal", nullable = false, precision = 18, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "tax_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal taxTotal;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "payment_term_days", nullable = false)
    private int paymentTermDays;

    @Column(name = "lead_time_days", nullable = false)
    private int leadTimeDays;

    @Column(name = "revision_no", nullable = false)
    private long revisionNo;

    @Column(name = "active_revision_id")
    private UUID activeRevisionId;

    @Column(name = "pending_revision_id")
    private UUID pendingRevisionId;

    @Column(name = "submitted_by")
    private UUID submittedBy;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "approved_by")
    private UUID approvedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Column(name = "confirmed_by")
    private UUID confirmedBy;
    @Column(name = "confirmed_at")
    private Instant confirmedAt;
    @Column(name = "closed_by")
    private UUID closedBy;
    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_kind", length = 16)
    private CloseKind closeKind;

    @Column(name = "close_reason", length = 255)
    private String closeReason;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "supplier_confirmation_status", nullable = false, length = 16)
    private SupplierConfirmationStatus supplierConfirmationStatus;

    @Column(name = "supplier_responded_at")
    private Instant supplierRespondedAt;

    @Column(name = "supplier_reference", length = 100)
    private String supplierReference;

    @Column(name = "supplier_response_note", length = 1000)
    private String supplierResponseNote;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<PoLineJpaEntity> lines = new ArrayList<>();

    protected PurchaseOrderJpaEntity() {
    }

    public PurchaseOrderJpaEntity(PurchaseOrder order) {
        super(order.id().value());
        this.poNumber = order.poNumber();
        this.type = order.type();
        this.productionOrderId = order.productionOrderId();
        this.supplierId = order.supplierId();
        this.warehouseId = order.warehouseId();
        this.currency = order.currency().getCurrencyCode();
        this.orderDate = order.orderDate();
        this.note = order.note();
        this.paymentTermDays = order.paymentTermDays();
        this.leadTimeDays = order.leadTimeDays();
    }

    /** Everything that can change after creation. */
    public void apply(PurchaseOrder o) {
        this.status = o.status();
        this.expectedAt = o.expectedAt();
        this.subtotal = o.subtotal().amount();
        this.taxTotal = o.taxTotal().amount();
        this.totalAmount = o.totalAmount().amount();
        this.revisionNo = o.revisionNo();
        this.activeRevisionId = o.activeRevisionId();
        this.pendingRevisionId = o.pendingRevisionId();
        this.submittedBy = o.submittedBy();
        this.submittedAt = o.submittedAt();
        this.approvedBy = o.approvedBy();
        this.approvedAt = o.approvedAt();
        this.confirmedBy = o.confirmedBy();
        this.confirmedAt = o.confirmedAt();
        this.closedBy = o.closedBy();
        this.closedAt = o.closedAt();
        this.closeKind = o.closeKind();
        this.closeReason = o.closeReason();
        this.cancelReason = o.cancelReason();
        this.supplierConfirmationStatus = o.supplierConfirmationStatus();
        this.supplierRespondedAt = o.supplierRespondedAt();
        this.supplierReference = o.supplierReference();
        this.supplierResponseNote = o.supplierResponseNote();
    }

    public void addLine(PoLineJpaEntity line) {
        line.attachTo(this);
        lines.add(line);
    }

    public String getPoNumber() { return poNumber; }
    public PurchaseOrder.Type getType() { return type; }
    public UUID getProductionOrderId() { return productionOrderId; }
    public UUID getSupplierId() { return supplierId; }
    public UUID getWarehouseId() { return warehouseId; }
    public PurchaseOrderStatus getStatus() { return status; }
    public String getCurrency() { return currency; }
    public LocalDate getOrderDate() { return orderDate; }
    public LocalDate getExpectedAt() { return expectedAt; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getTaxTotal() { return taxTotal; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getNote() { return note; }
    public int getPaymentTermDays() { return paymentTermDays; }
    public int getLeadTimeDays() { return leadTimeDays; }
    public long getRevisionNo() { return revisionNo; }
    public UUID getActiveRevisionId() { return activeRevisionId; }
    public UUID getPendingRevisionId() { return pendingRevisionId; }
    public UUID getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public UUID getConfirmedBy() { return confirmedBy; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public UUID getClosedBy() { return closedBy; }
    public Instant getClosedAt() { return closedAt; }
    public CloseKind getCloseKind() { return closeKind; }
    public String getCloseReason() { return closeReason; }
    public String getCancelReason() { return cancelReason; }
    public SupplierConfirmationStatus getSupplierConfirmationStatus() { return supplierConfirmationStatus; }
    public Instant getSupplierRespondedAt() { return supplierRespondedAt; }
    public String getSupplierReference() { return supplierReference; }
    public String getSupplierResponseNote() { return supplierResponseNote; }
    public List<PoLineJpaEntity> getLines() { return lines; }
}
