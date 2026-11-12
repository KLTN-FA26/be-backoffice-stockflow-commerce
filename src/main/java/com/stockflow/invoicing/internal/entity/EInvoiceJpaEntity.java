package com.stockflow.invoicing.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.EInvoiceType;
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
 * {@code invoicing.einvoice} (WF-05 E2). What an invoice is about is written once ({@code updatable = false}, and
 * {@code tg_einvoice_guard} refuses a change anyway); the rest follows the aggregate. Its lines are
 * {@code invoicing.einvoice_line}, written and read by the adapter alongside: rows with no life of their own.
 */
@Entity
@Table(name = "einvoice", schema = "invoicing")
public class EInvoiceJpaEntity extends BaseEntity {

    @Column(name = "code", nullable = false, updatable = false, length = 32)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 16)
    private EInvoiceType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private EInvoiceStatus status;

    @Column(name = "adjusted", nullable = false)
    private boolean adjusted;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "order_number", nullable = false, updatable = false, length = 32)
    private String orderNumber;

    @Column(name = "contract_number", updatable = false, length = 40)
    private String contractNumber;

    @Column(name = "shipment_id", updatable = false)
    private UUID shipmentId;

    @Column(name = "shipment_code", updatable = false, length = 48)
    private String shipmentCode;

    @Column(name = "delivery_batch_id", updatable = false)
    private UUID deliveryBatchId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "original_einvoice_id", updatable = false)
    private UUID originalEInvoiceId;

    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "template_code", length = 1)
    private String templateCode;

    @Column(name = "symbol", length = 6)
    private String symbol;

    @Column(name = "invoice_number")
    private Integer invoiceNumber;

    @Column(name = "tax_authority_code", length = 64)
    private String taxAuthorityCode;

    @Column(name = "lookup_code", length = 64)
    private String lookupCode;

    @Column(name = "issue_date", nullable = false, updatable = false)
    private LocalDate issueDate;

    @Column(name = "issue_due_at", nullable = false, updatable = false)
    private Instant issueDueAt;

    @Column(name = "due_warned_at")
    private Instant dueWarnedAt;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "seller_name", nullable = false, updatable = false, length = 200)
    private String sellerName;

    @Column(name = "seller_tax_code", nullable = false, updatable = false, length = 14)
    private String sellerTaxCode;

    @Column(name = "seller_address", nullable = false, updatable = false, length = 500)
    private String sellerAddress;

    @Column(name = "buyer_name", nullable = false, length = 200)
    private String buyerName;

    @Column(name = "buyer_tax_code", length = 14)
    private String buyerTaxCode;

    @Column(name = "buyer_address", length = 500)
    private String buyerAddress;

    @Column(name = "buyer_email", length = 320)
    private String buyerEmail;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "payment_method", nullable = false, updatable = false, length = 16)
    private String paymentMethod;

    @Column(name = "subtotal", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal subtotal;

    @Column(name = "tax_total", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal taxTotal;

    @Column(name = "grand_total", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal grandTotal;

    @Column(name = "amount_in_words", nullable = false, updatable = false, length = 500)
    private String amountInWords;

    @Column(name = "warnings", length = 200)
    private String warnings;

    @Column(name = "adjustment_reason", updatable = false, length = 500)
    private String adjustmentReason;

    @Column(name = "agreement_file_key", updatable = false, length = 500)
    private String agreementFileKey;

    @Column(name = "xml_file_key", length = 500)
    private String xmlFileKey;

    @Column(name = "pdf_file_key", length = 500)
    private String pdfFileKey;

    @Column(name = "rejection_code", length = 64)
    private String rejectionCode;

    @Column(name = "rejection_message", length = 500)
    private String rejectionMessage;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by")
    private UUID voidedBy;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    protected EInvoiceJpaEntity() {
    }

    /** What the invoice is about and what it says of money: fixed when it is drawn up. */
    public EInvoiceJpaEntity(UUID id, String code, EInvoiceType type, UUID orderId, String orderNumber,
                             String contractNumber, UUID shipmentId, String shipmentCode, UUID deliveryBatchId,
                             UUID customerId, UUID originalEInvoiceId, LocalDate issueDate, Instant issueDueAt,
                             String sellerName, String sellerTaxCode, String sellerAddress, String currency,
                             String paymentMethod, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal grandTotal,
                             String amountInWords, String adjustmentReason, String agreementFileKey) {
        super(id);
        this.code = code;
        this.type = type;
        this.orderId = orderId;
        this.orderNumber = orderNumber;
        this.contractNumber = contractNumber;
        this.shipmentId = shipmentId;
        this.shipmentCode = shipmentCode;
        this.deliveryBatchId = deliveryBatchId;
        this.customerId = customerId;
        this.originalEInvoiceId = originalEInvoiceId;
        this.issueDate = issueDate;
        this.issueDueAt = issueDueAt;
        this.sellerName = sellerName;
        this.sellerTaxCode = sellerTaxCode;
        this.sellerAddress = sellerAddress;
        this.currency = currency;
        this.paymentMethod = paymentMethod;
        this.subtotal = subtotal;
        this.taxTotal = taxTotal;
        this.grandTotal = grandTotal;
        this.amountInWords = amountInWords;
        this.adjustmentReason = adjustmentReason;
        this.agreementFileKey = agreementFileKey;
    }

    public void applyBuyer(String name, String taxCode, String address, String email, String warnings) {
        this.buyerName = name;
        this.buyerTaxCode = taxCode;
        this.buyerAddress = address;
        this.buyerEmail = email;
        this.warnings = warnings;
    }

    public void applyState(EInvoiceStatus status, boolean adjusted, Instant dueWarnedAt) {
        this.status = status;
        this.adjusted = adjusted;
        this.dueWarnedAt = dueWarnedAt;
    }

    public void applySubmission(String provider, int attempt, String transactionId, String templateCode,
                                String symbol, Instant submittedAt, UUID submittedBy, String rejectionCode,
                                String rejectionMessage) {
        this.provider = provider;
        this.attempt = attempt;
        this.transactionId = transactionId;
        this.templateCode = templateCode;
        this.symbol = symbol;
        this.submittedAt = submittedAt;
        this.submittedBy = submittedBy;
        this.rejectionCode = rejectionCode;
        this.rejectionMessage = rejectionMessage;
    }

    public void applyIssue(Integer invoiceNumber, String taxAuthorityCode, String lookupCode, Instant signedAt,
                           String xmlFileKey, String pdfFileKey, Instant issuedAt) {
        this.invoiceNumber = invoiceNumber;
        this.taxAuthorityCode = taxAuthorityCode;
        this.lookupCode = lookupCode;
        this.signedAt = signedAt;
        this.xmlFileKey = xmlFileKey;
        this.pdfFileKey = pdfFileKey;
        this.issuedAt = issuedAt;
    }

    public void applyVoid(Instant voidedAt, UUID voidedBy, String voidReason) {
        this.voidedAt = voidedAt;
        this.voidedBy = voidedBy;
        this.voidReason = voidReason;
    }

    public String getCode() { return code; }
    public EInvoiceType getType() { return type; }
    public EInvoiceStatus getStatus() { return status; }
    public boolean isAdjusted() { return adjusted; }
    public UUID getOrderId() { return orderId; }
    public String getOrderNumber() { return orderNumber; }
    public String getContractNumber() { return contractNumber; }
    public UUID getShipmentId() { return shipmentId; }
    public String getShipmentCode() { return shipmentCode; }
    public UUID getDeliveryBatchId() { return deliveryBatchId; }
    public UUID getCustomerId() { return customerId; }
    public UUID getOriginalEInvoiceId() { return originalEInvoiceId; }
    public String getProvider() { return provider; }
    public int getAttempt() { return attempt; }
    public String getTransactionId() { return transactionId; }
    public String getTemplateCode() { return templateCode; }
    public String getSymbol() { return symbol; }
    public Integer getInvoiceNumber() { return invoiceNumber; }
    public String getTaxAuthorityCode() { return taxAuthorityCode; }
    public String getLookupCode() { return lookupCode; }
    public LocalDate getIssueDate() { return issueDate; }
    public Instant getIssueDueAt() { return issueDueAt; }
    public Instant getDueWarnedAt() { return dueWarnedAt; }
    public Instant getSignedAt() { return signedAt; }
    public String getSellerName() { return sellerName; }
    public String getSellerTaxCode() { return sellerTaxCode; }
    public String getSellerAddress() { return sellerAddress; }
    public String getBuyerName() { return buyerName; }
    public String getBuyerTaxCode() { return buyerTaxCode; }
    public String getBuyerAddress() { return buyerAddress; }
    public String getBuyerEmail() { return buyerEmail; }
    public String getCurrency() { return currency; }
    public String getPaymentMethod() { return paymentMethod; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getTaxTotal() { return taxTotal; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public String getAmountInWords() { return amountInWords; }
    public String getWarnings() { return warnings; }
    public String getAdjustmentReason() { return adjustmentReason; }
    public String getAgreementFileKey() { return agreementFileKey; }
    public String getXmlFileKey() { return xmlFileKey; }
    public String getPdfFileKey() { return pdfFileKey; }
    public String getRejectionCode() { return rejectionCode; }
    public String getRejectionMessage() { return rejectionMessage; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getSubmittedBy() { return submittedBy; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getVoidedAt() { return voidedAt; }
    public UUID getVoidedBy() { return voidedBy; }
    public String getVoidReason() { return voidReason; }
}
