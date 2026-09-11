package com.stockflow.payment.internal.entity;

import com.stockflow.payment.internal.domain.PaymentMethod;
import com.stockflow.payment.internal.domain.PaymentStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** JPA mapping of a payment (table {@code payment.payment}). Not the domain model. STARTER ENTITY. */
@Entity
@Table(name = "payment", schema = "payment")
public class PaymentJpaEntity extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 32)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "provider_ref", length = 128)
    private String providerRef;

    @Column(name = "captured_at")
    private Instant capturedAt;

    protected PaymentJpaEntity() {
    }

    public PaymentJpaEntity(UUID id, UUID orderId, BigDecimal amount, String currency,
                            PaymentMethod method, PaymentStatus status, String providerRef,
                            Instant capturedAt) {
        super(id);
        this.orderId = orderId;
        this.amount = amount;
        this.currency = currency;
        this.method = method;
        this.status = status;
        this.providerRef = providerRef;
        this.capturedAt = capturedAt;
    }

    public UUID getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public PaymentMethod getMethod() { return method; }
    public PaymentStatus getStatus() { return status; }
    public String getProviderRef() { return providerRef; }
    public Instant getCapturedAt() { return capturedAt; }
}
