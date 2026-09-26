package com.stockflow.procurement.internal.entity;

import com.stockflow.procurement.internal.domain.PurchaseOrderStatus;
import com.stockflow.procurement.internal.domain.SupplierConfirmationStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA mapping of a purchase order (table {@code procurement.purchase_order}). Not the domain model.
 *
 * <p>{@code supplierId} is a same-schema reference. {@code lines} is cascade-managed with the
 * order — same shape as {@code OrderJpaEntity.lines} — since a PO line has no independent lifecycle
 * of its own.</p>
 */
@Entity
@Table(name = "purchase_order", schema = "procurement",
        uniqueConstraints = @UniqueConstraint(name = "uk_purchase_order_number", columnNames = "po_number"))
public class PurchaseOrderJpaEntity extends BaseEntity {

    @Column(name = "po_number", nullable = false, length = 64)
    private String poNumber;

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PurchaseOrderStatus status;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "expected_at")
    private LocalDate expectedAt;

    @Column(name = "cancellation_reason", length = 1000)
    private String cancellationReason;

    @Column(name = "close_short_reason", length = 1000)
    private String closeShortReason;

    @Column(name = "payment_term_days", nullable = false) private int paymentTermDays;
    @Column(name = "lead_time_days", nullable = false) private int leadTimeDays;
    @Column(name = "sent_at") private Instant sentAt;
    @Column(name = "receipt_completed_at") private Instant receiptCompletedAt;
    @Enumerated(EnumType.STRING) @Column(name = "supplier_confirmation_status", nullable = false, length = 16)
    private SupplierConfirmationStatus supplierConfirmationStatus;
    @Column(name = "supplier_responded_at") private Instant supplierRespondedAt;
    @Column(name = "supplier_reference", length = 100) private String supplierReference;
    @Column(name = "supplier_response_note", length = 1000) private String supplierResponseNote;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @org.hibernate.annotations.BatchSize(size = 50)
    private List<POLineJpaEntity> lines = new ArrayList<>();

    protected PurchaseOrderJpaEntity() {
    }

    public PurchaseOrderJpaEntity(UUID id, String poNumber, UUID supplierId, PurchaseOrderStatus status,
                                  String currency, BigDecimal totalAmount, LocalDate expectedAt,
                                  String cancellationReason, String closeShortReason, int paymentTermDays,
                                  int leadTimeDays, Instant sentAt, SupplierConfirmationStatus supplierConfirmationStatus,
                                  Instant supplierRespondedAt, String supplierReference, String supplierResponseNote) {
        super(id);
        this.poNumber = poNumber;
        this.supplierId = supplierId;
        this.status = status;
        this.currency = currency;
        this.totalAmount = totalAmount;
        this.expectedAt = expectedAt;
        this.cancellationReason = cancellationReason;
        this.closeShortReason = closeShortReason;
        this.paymentTermDays = paymentTermDays;
        this.leadTimeDays = leadTimeDays;
        this.sentAt = sentAt;
        this.supplierConfirmationStatus = supplierConfirmationStatus;
        this.supplierRespondedAt = supplierRespondedAt;
        this.supplierReference = supplierReference;
        this.supplierResponseNote = supplierResponseNote;
    }

    /** Copies every editable field onto a managed row, so Hibernate's dirty checking writes the UPDATE. */
    public void apply(PurchaseOrderStatus status, BigDecimal totalAmount, String cancellationReason,
                      String closeShortReason, Instant sentAt, SupplierConfirmationStatus confirmationStatus,
                      Instant respondedAt, String supplierReference, String supplierResponseNote) {
        this.status = status;
        this.totalAmount = totalAmount;
        this.cancellationReason = cancellationReason;
        this.closeShortReason = closeShortReason;
        this.sentAt = sentAt;
        this.supplierConfirmationStatus = confirmationStatus;
        this.supplierRespondedAt = respondedAt;
        this.supplierReference = supplierReference;
        this.supplierResponseNote = supplierResponseNote;
    }

    public void replaceLines(List<POLineJpaEntity> replacement) {
        this.lines.clear();
        replacement.forEach(child -> {
            child.attachTo(this);
            this.lines.add(child);
        });
    }

    public String getPoNumber() { return poNumber; }
    public UUID getSupplierId() { return supplierId; }
    public PurchaseOrderStatus getStatus() { return status; }
    public String getCurrency() { return currency; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public LocalDate getExpectedAt() { return expectedAt; }
    public void confirmExpectedAt(LocalDate expectedAt) { this.expectedAt = expectedAt; }
    public String getCancellationReason() { return cancellationReason; }
    public String getCloseShortReason() { return closeShortReason; }
    public int getPaymentTermDays() { return paymentTermDays; }
    public int getLeadTimeDays() { return leadTimeDays; }
    public Instant getSentAt() { return sentAt; }
    public Instant getReceiptCompletedAt() { return receiptCompletedAt; }
    public void recordCompletion(Instant completedAt) { this.receiptCompletedAt = completedAt; }
    public SupplierConfirmationStatus getSupplierConfirmationStatus() { return supplierConfirmationStatus; }
    public Instant getSupplierRespondedAt() { return supplierRespondedAt; }
    public String getSupplierReference() { return supplierReference; }
    public String getSupplierResponseNote() { return supplierResponseNote; }
    public List<POLineJpaEntity> getLines() { return lines; }
}
