package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.procurement.internal.domain.InvoiceVarianceKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code procurement.supplier_invoice_variances} (V20261101050000): one thing the last match of an invoice found. */
@Entity
@Table(name = "supplier_invoice_variances", schema = "procurement")
public class SupplierInvoiceVarianceJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private SupplierInvoiceJpaEntity invoice;

    @Column(name = "invoice_line_id", updatable = false)
    private UUID invoiceLineId;

    @Column(name = "po_id", updatable = false)
    private UUID purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32, updatable = false)
    private InvoiceVarianceKind kind;

    @Column(name = "expected_value", nullable = false, precision = 18, scale = 3, updatable = false)
    private BigDecimal expectedValue;

    @Column(name = "invoiced_value", nullable = false, precision = 18, scale = 3, updatable = false)
    private BigDecimal invoicedValue;

    @Column(name = "variance_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal varianceAmount;

    protected SupplierInvoiceVarianceJpaEntity() {
    }

    public SupplierInvoiceVarianceJpaEntity(UUID id, UUID invoiceLineId, UUID purchaseOrderId, InvoiceVarianceKind kind,
                                            BigDecimal expectedValue, BigDecimal invoicedValue,
                                            BigDecimal varianceAmount) {
        super(id);
        this.invoiceLineId = invoiceLineId;
        this.purchaseOrderId = purchaseOrderId;
        this.kind = kind;
        this.expectedValue = expectedValue;
        this.invoicedValue = invoicedValue;
        this.varianceAmount = varianceAmount;
    }

    void attachTo(SupplierInvoiceJpaEntity owner) {
        this.invoice = owner;
    }

    public UUID getInvoiceLineId() { return invoiceLineId; }
    public UUID getPurchaseOrderId() { return purchaseOrderId; }
    public InvoiceVarianceKind getKind() { return kind; }
    public BigDecimal getExpectedValue() { return expectedValue; }
    public BigDecimal getInvoicedValue() { return invoicedValue; }
    public BigDecimal getVarianceAmount() { return varianceAmount; }
}
