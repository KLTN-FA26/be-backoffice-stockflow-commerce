package com.stockflow.payment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.payment.internal.domain.BankTransactionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code payment.bank_transaction}: a transfer the bank feed reported. Inserted by the adapter with
 * {@code ON CONFLICT DO NOTHING} - two copies of one webhook call arriving together keep one row - and read and
 * decided through this mapping; what SePay reported never changes after.
 */
@Entity
@Table(name = "bank_transaction", schema = "payment")
public class BankTransactionJpaEntity extends BaseEntity {

    @Column(name = "provider", nullable = false, updatable = false, length = 16)
    private String provider;

    @Column(name = "provider_transaction_id", nullable = false, updatable = false, length = 64)
    private String providerTransactionId;

    @Column(name = "source", nullable = false, updatable = false, length = 16)
    private String source;

    @Column(name = "dedupe_key", nullable = false, updatable = false, length = 128)
    private String dedupeKey;

    @Column(name = "account_number", updatable = false, length = 32)
    private String accountNumber;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "content", updatable = false, length = 500)
    private String content;

    @Column(name = "bank_reference", updatable = false, length = 100)
    private String bankReference;

    @Column(name = "payment_code", updatable = false, length = 64)
    private String paymentCode;

    @Column(name = "transaction_at", nullable = false, updatable = false)
    private Instant transactionAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String raw;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BankTransactionStatus status;

    @Column(name = "review_reason", length = 32)
    private String reviewReason;

    @Column(name = "review_note", length = 500)
    private String reviewNote;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "installment_id")
    private UUID installmentId;

    @Column(name = "receipt_id")
    private UUID receiptId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "ignore_reason", length = 500)
    private String ignoreReason;

    protected BankTransactionJpaEntity() {
    }

    /** What a decision changes; everything SePay reported stays as it was kept. */
    @SuppressWarnings("java:S107")
    public void decide(BankTransactionStatus status, String reviewReason, String reviewNote, UUID customerId,
                       UUID installmentId, UUID receiptId, Instant decidedAt, UUID reviewedBy, Instant reviewedAt,
                       String ignoreReason) {
        this.status = status;
        this.reviewReason = reviewReason;
        this.reviewNote = reviewNote;
        this.customerId = customerId;
        this.installmentId = installmentId;
        this.receiptId = receiptId;
        this.decidedAt = decidedAt;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.ignoreReason = ignoreReason;
    }

    public String getProvider() { return provider; }
    public String getProviderTransactionId() { return providerTransactionId; }
    public String getSource() { return source; }
    public String getDedupeKey() { return dedupeKey; }
    public String getAccountNumber() { return accountNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getContent() { return content; }
    public String getBankReference() { return bankReference; }
    public String getPaymentCode() { return paymentCode; }
    public Instant getTransactionAt() { return transactionAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getRaw() { return raw; }
    public BankTransactionStatus getStatus() { return status; }
    public String getReviewReason() { return reviewReason; }
    public String getReviewNote() { return reviewNote; }
    public UUID getCustomerId() { return customerId; }
    public UUID getInstallmentId() { return installmentId; }
    public UUID getReceiptId() { return receiptId; }
    public Instant getDecidedAt() { return decidedAt; }
    public UUID getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getIgnoreReason() { return ignoreReason; }
}
