package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code procurement.supplier_invoice_lines}: one billed line. Written once and replaced whole when the accountant
 * corrects the invoice; {@code check_supplier_invoice_line} holds it to the supplier's PO lines.
 */
@Entity
@Table(name = "supplier_invoice_lines", schema = "procurement")
public class SupplierInvoiceLineJpaEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private SupplierInvoiceJpaEntity invoice;

    @Column(name = "po_line_id", nullable = false, updatable = false)
    private UUID poLineId;

    @Column(name = "receipt_line_id", updatable = false)
    private UUID receiptLineId;

    @Column(name = "invoiced_qty", nullable = false, precision = 18, scale = 3, updatable = false)
    private BigDecimal invoicedQuantity;

    @Column(name = "unit_price", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 2, updatable = false)
    private BigDecimal taxRate;

    /** Net plus tax, as on a purchase order line. */
    @Column(name = "line_total", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal lineTotal;

    protected SupplierInvoiceLineJpaEntity() {
    }

    public SupplierInvoiceLineJpaEntity(UUID id, UUID poLineId, UUID receiptLineId, BigDecimal invoicedQuantity,
                                        BigDecimal unitPrice, BigDecimal taxRate, BigDecimal lineTotal) {
        super(id);
        this.poLineId = poLineId;
        this.receiptLineId = receiptLineId;
        this.invoicedQuantity = invoicedQuantity;
        this.unitPrice = unitPrice;
        this.taxRate = taxRate;
        this.lineTotal = lineTotal;
    }

    void attachTo(SupplierInvoiceJpaEntity owner) {
        this.invoice = owner;
    }

    public UUID getPoLineId() { return poLineId; }
    public UUID getReceiptLineId() { return receiptLineId; }
    public BigDecimal getInvoicedQuantity() { return invoicedQuantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getTaxRate() { return taxRate; }
    public BigDecimal getLineTotal() { return lineTotal; }
}
