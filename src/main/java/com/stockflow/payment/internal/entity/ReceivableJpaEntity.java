package com.stockflow.payment.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.payment.internal.domain.ReceivableStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** {@code payment.receivable} (SCRUM-431). */
@Entity
@Table(name = "receivable", schema = "payment")
public class ReceivableJpaEntity extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "kind", nullable = false, length = 16, updatable = false)
    private String kind;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "paid_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal paidAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ReceivableStatus status;

    @Column(name = "settled_at")
    private Instant settledAt;

    protected ReceivableJpaEntity() {
    }

    public ReceivableJpaEntity(UUID id, UUID orderId, UUID customerId, BigDecimal amount, String currency,
                               Instant issuedAt, LocalDate dueDate) {
        super(id);
        this.orderId = orderId;
        this.customerId = customerId;
        this.kind = "CREDIT_ORDER";
        this.amount = amount;
        this.paidAmount = BigDecimal.ZERO;
        this.currency = currency;
        this.issuedAt = issuedAt;
        this.dueDate = dueDate;
        this.status = ReceivableStatus.OPEN;
    }

    public void apply(BigDecimal paidAmount, ReceivableStatus status, Instant settledAt) {
        this.paidAmount = paidAmount;
        this.status = status;
        this.settledAt = settledAt;
    }

    public UUID getOrderId() { return orderId; }
    public UUID getCustomerId() { return customerId; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public String getCurrency() { return currency; }
    public Instant getIssuedAt() { return issuedAt; }
    public LocalDate getDueDate() { return dueDate; }
    public ReceivableStatus getStatus() { return status; }
    public Instant getSettledAt() { return settledAt; }
}
