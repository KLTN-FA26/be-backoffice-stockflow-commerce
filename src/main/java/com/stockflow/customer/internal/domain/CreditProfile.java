package com.stockflow.customer.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The credit of one customer (kltn-docs 18 §3, 15 BR-12; SCRUM-427): the credit limit and how many days after delivery
 * an installment paid after delivery has to be paid. Since 4B-10 nothing more: the payment terms a customer could be
 * sold on per order (prepaid, deposit, credit), the default term, the deposit share and the deposit balance policy went
 * with the money ledger - a contract's installment plan says how the customer pays (15 BR-01).
 *
 * <p>Every change names who approved it and when (18 BR-02). The table says the same rules again
 * ({@code ck_credit_profile_credit}).</p>
 */
public final class CreditProfile extends AggregateRoot {

    private final UUID id;
    private final UUID customerId;
    private BigDecimal creditLimit;
    private Integer creditTermDays;
    private final String currency;
    private UUID approvedBy;
    private Instant approvedAt;
    private String note;
    private final long version;

    public CreditProfile(UUID id, UUID customerId, BigDecimal creditLimit, Integer creditTermDays, String currency,
                         UUID approvedBy, Instant approvedAt, String note, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.currency = currency == null ? "VND" : currency;
        this.version = version;
        apply(creditLimit, creditTermDays, approvedBy, approvedAt, note);
    }

    public static CreditProfile create(UUID customerId, BigDecimal creditLimit, Integer creditTermDays, UUID approvedBy,
                                       Instant now, String note) {
        return new CreditProfile(Identifiers.newId(), customerId, creditLimit, creditTermDays, "VND", approvedBy, now,
                note, 0L);
    }

    /**
     * Replace the credit. Contracts already signed keep the plan they were signed on (15 BR-12).
     *
     * @throws BusinessException {@code CREDIT_PROFILE_INVALID} when the credit contradicts itself
     */
    public void change(BigDecimal creditLimit, Integer creditTermDays, UUID approvedBy, Instant now, String note) {
        apply(creditLimit, creditTermDays, approvedBy, now, note);
    }

    private void apply(BigDecimal limit, Integer days, UUID by, Instant at, String text) {
        if ((limit == null) != (days == null)) {
            throw invalid("A credit limit and the days to pay are given together, or neither");
        }
        if (limit != null && limit.signum() < 0) {
            throw invalid("The credit limit cannot be negative");
        }
        if (days != null && (days < 0 || days > 365)) {
            throw invalid("The days to pay are between 0 and 365");
        }
        this.creditLimit = limit;
        this.creditTermDays = days;
        this.approvedBy = Objects.requireNonNull(by, "approvedBy");
        this.approvedAt = Objects.requireNonNull(at, "approvedAt");
        this.note = text == null || text.isBlank() ? null : text.trim();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.CREDIT_PROFILE_INVALID, message);
    }

    /** Whether the customer has a limit at all. */
    public boolean hasCredit() { return creditLimit != null; }

    public UUID id() { return id; }
    public UUID customerId() { return customerId; }
    public BigDecimal creditLimit() { return creditLimit; }
    public Integer creditTermDays() { return creditTermDays; }
    public String currency() { return currency; }
    public UUID approvedBy() { return approvedBy; }
    public Instant approvedAt() { return approvedAt; }
    public String note() { return note; }
    public long version() { return version; }
}
