package com.stockflow.procurement.internal.entity;

import com.stockflow.procurement.internal.domain.InvoiceStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPA mapping of a supplier invoice (table {@code procurement.supplier_invoice}). STARTER ENTITY.
 *
 * <p>{@code supplierId} and {@code purchaseOrderId} are same-schema references. The three-way match
 * (PO ↔ receipt ↔ invoice) is a TODO once the matching use case is built.</p>
 */
@Entity
@Table(name = "supplier_invoice", schema = "procurement",
        uniqueConstraints = @UniqueConstraint(name = "uk_supplier_invoice_number", columnNames = "invoice_number"))
public class SupplierInvoiceJpaEntity extends BaseEntity {

    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;

    @Column(name = "purchase_order_id")
    private UUID purchaseOrderId;

    @Column(name = "invoice_number", nullable = false, length = 64)
    private String invoiceNumber;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private InvoiceStatus status;

    protected SupplierInvoiceJpaEntity() {
    }

    public SupplierInvoiceJpaEntity(UUID id, UUID supplierId, UUID purchaseOrderId, String invoiceNumber,
                                    BigDecimal amount, String currency, InvoiceStatus status) {
        super(id);
        this.supplierId = supplierId;
        this.purchaseOrderId = purchaseOrderId;
        this.invoiceNumber = invoiceNumber;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
    }

    public UUID getSupplierId() { return supplierId; }
    public UUID getPurchaseOrderId() { return purchaseOrderId; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public InvoiceStatus getStatus() { return status; }
}
