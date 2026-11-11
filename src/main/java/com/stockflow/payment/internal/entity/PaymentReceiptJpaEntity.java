package com.stockflow.payment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.payment.internal.domain.PaymentReceiptStatus;
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
 * {@code payment.payment_receipt} (4B-6): written once (15 BR-04); only its void is ever added. Its lines are
 * {@code payment.payment_receipt_line}, written and read by the adapter alongside - append-only rows with no life of
 * their own.
 */
@Entity
@Table(name = "payment_receipt", schema = "payment")
public class PaymentReceiptJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "reference", nullable = false, updatable = false, length = 100)
    private String reference;

    @Column(name = "received_on", nullable = false, updatable = false)
    private LocalDate receivedOn;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "note", updatable = false, length = 500)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentReceiptStatus status;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "voided_by")
    private UUID voidedBy;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    protected PaymentReceiptJpaEntity() {
    }

    public PaymentReceiptJpaEntity(UUID id, UUID customerId, String reference, LocalDate receivedOn, BigDecimal amount,
                                   String currency, String note, UUID recordedBy, Instant recordedAt) {
        super(id);
        this.customerId = customerId;
        this.reference = reference;
        this.receivedOn = receivedOn;
        this.amount = amount;
        this.currency = currency;
        this.note = note;
        this.status = PaymentReceiptStatus.RECORDED;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
    }

    public void voided(UUID by, Instant at, String reason) {
        this.status = PaymentReceiptStatus.VOIDED;
        this.voidedBy = by;
        this.voidedAt = at;
        this.voidReason = reason;
    }

    public UUID getCustomerId() { return customerId; }
    public String getReference() { return reference; }
    public LocalDate getReceivedOn() { return receivedOn; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getNote() { return note; }
    public PaymentReceiptStatus getStatus() { return status; }
    public UUID getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
    public UUID getVoidedBy() { return voidedBy; }
    public Instant getVoidedAt() { return voidedAt; }
    public String getVoidReason() { return voidReason; }
}
