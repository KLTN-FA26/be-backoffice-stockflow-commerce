package com.stockflow.procurement.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.procurement.internal.domain.SupplierInvoiceStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** {@code procurement.supplier_invoices} (V20260928002300, V20261101050000): a supplier's invoice and its last match. */
@Entity
@Table(name = "supplier_invoices", schema = "procurement")
public class SupplierInvoiceJpaEntity extends BaseEntity {

    @Column(name = "invoice_number", nullable = false, length = 64)
    private String invoiceNumber;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    /** Correctable while the invoice is open; {@code check_supplier_invoice} holds it to the supplier's orders. */
    @Column(name = "po_id")
    private UUID purchaseOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SupplierInvoiceStatus status;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "subtotal", nullable = false, precision = 18, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "tax_total", nullable = false, precision = 18, scale = 2)
    private BigDecimal taxTotal;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalAmount;

    /** The scan's storage key. */
    @Column(name = "file_url")
    private String fileKey;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_content_type", length = 100)
    private String fileContentType;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "received_by", updatable = false)
    private UUID receivedBy;

    @Column(name = "matched_at")
    private Instant matchedAt;

    @Column(name = "matched_by")
    private UUID matchedBy;

    @Column(name = "price_tolerance_percent", precision = 5, scale = 2)
    private BigDecimal priceTolerancePercent;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by")
    private UUID voidedBy;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<SupplierInvoiceLineJpaEntity> lines = new ArrayList<>();

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<SupplierInvoiceVarianceJpaEntity> variances = new ArrayList<>();

    protected SupplierInvoiceJpaEntity() {
    }

    public SupplierInvoiceJpaEntity(UUID id, UUID supplierId, String currency, UUID receivedBy) {
        super(id);
        this.supplierId = supplierId;
        this.currency = currency;
        this.receivedBy = receivedBy;
    }

    /** The header as the aggregate now has it, totals worked out from its lines. */
    public void apply(UUID purchaseOrderId, String invoiceNumber, LocalDate invoiceDate, LocalDate dueDate, String note,
                      SupplierInvoiceStatus status, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal totalAmount) {
        this.purchaseOrderId = purchaseOrderId;
        this.invoiceNumber = invoiceNumber;
        this.invoiceDate = invoiceDate;
        this.dueDate = dueDate;
        this.note = note;
        this.status = status;
        this.subtotal = subtotal;
        this.taxTotal = taxTotal;
        this.totalAmount = totalAmount;
    }

    public void applyMatch(Instant matchedAt, UUID matchedBy, BigDecimal priceTolerancePercent) {
        this.matchedAt = matchedAt;
        this.matchedBy = matchedBy;
        this.priceTolerancePercent = priceTolerancePercent;
    }

    public void applyCancel(Instant voidedAt, UUID voidedBy, String voidReason) {
        this.voidedAt = voidedAt;
        this.voidedBy = voidedBy;
        this.voidReason = voidReason;
    }

    public void applyScan(String fileKey, String fileName, String fileContentType) {
        this.fileKey = fileKey;
        this.fileName = fileName;
        this.fileContentType = fileContentType;
    }

    public void addLine(SupplierInvoiceLineJpaEntity line) {
        line.attachTo(this);
        lines.add(line);
    }

    public void addVariance(SupplierInvoiceVarianceJpaEntity variance) {
        variance.attachTo(this);
        variances.add(variance);
    }

    public String getInvoiceNumber() { return invoiceNumber; }
    public UUID getSupplierId() { return supplierId; }
    public UUID getPurchaseOrderId() { return purchaseOrderId; }
    public SupplierInvoiceStatus getStatus() { return status; }
    public String getCurrency() { return currency; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public LocalDate getDueDate() { return dueDate; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getTaxTotal() { return taxTotal; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getFileKey() { return fileKey; }
    public String getFileName() { return fileName; }
    public String getFileContentType() { return fileContentType; }
    public String getNote() { return note; }
    public UUID getReceivedBy() { return receivedBy; }
    public Instant getMatchedAt() { return matchedAt; }
    public UUID getMatchedBy() { return matchedBy; }
    public BigDecimal getPriceTolerancePercent() { return priceTolerancePercent; }
    public Instant getVoidedAt() { return voidedAt; }
    public UUID getVoidedBy() { return voidedBy; }
    public String getVoidReason() { return voidReason; }
    public List<SupplierInvoiceLineJpaEntity> getLines() { return lines; }
    public List<SupplierInvoiceVarianceJpaEntity> getVariances() { return variances; }
}
