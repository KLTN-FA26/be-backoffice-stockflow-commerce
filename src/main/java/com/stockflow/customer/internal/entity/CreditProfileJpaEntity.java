package com.stockflow.customer.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.customer.api.CommercialTerm;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code customer.credit_profile}: one row per customer with terms (SCRUM-427). */
@Entity
@Table(name = "credit_profile", schema = "customer")
public class CreditProfileJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "allow_prepaid", nullable = false)
    private boolean allowPrepaid;

    @Column(name = "allow_deposit", nullable = false)
    private boolean allowDeposit;

    @Column(name = "allow_credit", nullable = false)
    private boolean allowCredit;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_payment_term", nullable = false, length = 16)
    private CommercialTerm defaultPaymentTerm;

    @Column(name = "deposit_percent", precision = 5, scale = 2)
    private BigDecimal depositPercent;

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

    public void apply(boolean allowPrepaid, boolean allowDeposit, boolean allowCredit, CommercialTerm defaultPaymentTerm,
                      BigDecimal depositPercent, BigDecimal creditLimit, Integer creditTermDays, UUID approvedBy,
                      Instant approvedAt, String note) {
        this.allowPrepaid = allowPrepaid;
        this.allowDeposit = allowDeposit;
        this.allowCredit = allowCredit;
        this.defaultPaymentTerm = defaultPaymentTerm;
        this.depositPercent = depositPercent;
        this.creditLimit = creditLimit;
        this.creditTermDays = creditTermDays;
        this.approvedBy = approvedBy;
        this.approvedAt = approvedAt;
        this.note = note;
    }

    public UUID getCustomerId() { return customerId; }
    public boolean isAllowPrepaid() { return allowPrepaid; }
    public boolean isAllowDeposit() { return allowDeposit; }
    public boolean isAllowCredit() { return allowCredit; }
    public CommercialTerm getDefaultPaymentTerm() { return defaultPaymentTerm; }
    public BigDecimal getDepositPercent() { return depositPercent; }
    public BigDecimal getCreditLimit() { return creditLimit; }
    public Integer getCreditTermDays() { return creditTermDays; }
    public String getCurrency() { return currency; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getNote() { return note; }
}
