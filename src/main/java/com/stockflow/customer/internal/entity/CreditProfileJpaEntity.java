package com.stockflow.customer.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code customer.credit_profile}: one row per customer with credit set (SCRUM-427): the limit and the days to pay. The
 * payment-term columns ({@code allow_*}, {@code default_payment_term}, {@code deposit_percent},
 * {@code deposit_balance_policy}) were unmapped by 4B-10 and dropped by 4B-11: a customer pays by the installments of
 * its contracts (kltn-docs 15).
 */
@Entity
@Table(name = "credit_profile", schema = "customer")
public class CreditProfileJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "credit_limit", precision = 19, scale = 4)
    private BigDecimal creditLimit;

    @Column(name = "credit_term_days")
    private Integer creditTermDays;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "approved_by", nullable = false)
    private UUID approvedBy;

    @Column(name = "approved_at", nullable = false)
    private Instant approvedAt;

    @Column(name = "note", length = 1000)
    private String note;

    protected CreditProfileJpaEntity() {
    }

    public CreditProfileJpaEntity(UUID id, UUID customerId, String currency) {
        super(id);
        this.customerId = customerId;
        this.currency = currency;
    }

    public void apply(BigDecimal creditLimit, Integer creditTermDays, UUID approvedBy, Instant approvedAt,
                      String note) {
        this.creditLimit = creditLimit;
        this.creditTermDays = creditTermDays;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.note = note;
    }

    public UUID getCustomerId() { return customerId; }
    public BigDecimal getCreditLimit() { return creditLimit; }
    public Integer getCreditTermDays() { return creditTermDays; }
    public String getCurrency() { return currency; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getNote() { return note; }
}
