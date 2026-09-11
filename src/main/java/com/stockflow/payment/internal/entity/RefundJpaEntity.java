package com.stockflow.payment.internal.entity;

import com.stockflow.payment.internal.domain.RefundStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping of a refund (table {@code payment.refund}). Not the domain model. STARTER ENTITY. */
@Entity
@Table(name = "refund", schema = "payment")
public class RefundJpaEntity extends BaseEntity {

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reason", length = 255)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RefundStatus status;

    @Column(name = "refunded_at")
    private Instant refundedAt;

    protected RefundJpaEntity() {
    }

    public RefundJpaEntity(UUID id, UUID paymentId, BigDecimal amount, String currency,
                           String reason, RefundStatus status, Instant refundedAt) {
        super(id);
        this.paymentId = paymentId;
        this.amount = amount;
        this.currency = currency;
        this.reason = reason;
        this.status = status;
        this.refundedAt = refundedAt;
    }

    public UUID getPaymentId() { return paymentId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public RefundStatus getStatus() { return status; }
    public Instant getRefundedAt() { return refundedAt; }
}
