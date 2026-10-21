package com.stockflow.customer.internal.domain;

import com.stockflow.common.domain.AggregateRoot;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CommercialTerm;
import com.stockflow.customer.api.DepositBalancePolicy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The commercial terms of one customer (kltn-docs 18 §3, 15 §3; SCRUM-427): which payment terms
 * the customer may use, the default deposit share, the credit limit and how many days a credit
 * order has to be paid after delivery. G2-3: with deposits, how the balance is collected (15 §4.2 step 3).
 *
 * <p>Every change names who approved it and when (18 BR-02). The table says the same rules again
 * ({@code ck_credit_profile_*}).</p>
 */
public final class CreditProfile extends AggregateRoot {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final UUID id;
    private final UUID customerId;
    private boolean allowPrepaid;
    private boolean allowDeposit;
    private boolean allowCredit;
    private CommercialTerm defaultTerm;
    private BigDecimal depositPercent;
    private BigDecimal creditLimit;
    private Integer creditTermDays;
    private DepositBalancePolicy depositBalancePolicy;
    private final String currency;
    private UUID approvedBy;
    private Instant approvedAt;
    private String note;
    private final long version;

    public CreditProfile(UUID id, UUID customerId, boolean allowPrepaid, boolean allowDeposit, boolean allowCredit,
                         CommercialTerm defaultTerm, BigDecimal depositPercent, BigDecimal creditLimit,
                         Integer creditTermDays, String currency, UUID approvedBy, Instant approvedAt, String note,
                         long version) {
        this(id, customerId, allowPrepaid, allowDeposit, allowCredit, defaultTerm, depositPercent, creditLimit,
                creditTermDays, null, currency, approvedBy, approvedAt, note, version);
    }

    public CreditProfile(UUID id, UUID customerId, boolean allowPrepaid, boolean allowDeposit, boolean allowCredit,
                         CommercialTerm defaultTerm, BigDecimal depositPercent, BigDecimal creditLimit,
                         Integer creditTermDays, DepositBalancePolicy balancePolicy, String currency, UUID approvedBy,
                         Instant approvedAt, String note, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.currency = currency == null ? "VND" : currency;
        this.version = version;
        apply(allowPrepaid, allowDeposit, allowCredit, defaultTerm, depositPercent, creditLimit, creditTermDays,
                balancePolicy, approvedBy, approvedAt, note);
    }

    public static CreditProfile create(UUID customerId, boolean allowPrepaid, boolean allowDeposit, boolean allowCredit,
                                       CommercialTerm defaultTerm, BigDecimal depositPercent, BigDecimal creditLimit,
                                       Integer creditTermDays, UUID approvedBy, Instant now, String note) {
        return create(customerId, allowPrepaid, allowDeposit, allowCredit, defaultTerm, depositPercent, creditLimit,
                creditTermDays, null, approvedBy, now, note);
    }

    public static CreditProfile create(UUID customerId, boolean allowPrepaid, boolean allowDeposit, boolean allowCredit,
                                       CommercialTerm defaultTerm, BigDecimal depositPercent, BigDecimal creditLimit,
                                       Integer creditTermDays, DepositBalancePolicy balancePolicy, UUID approvedBy,
                                       Instant now, String note) {
        return new CreditProfile(Identifiers.newId(), customerId, allowPrepaid, allowDeposit, allowCredit, defaultTerm,
                depositPercent, creditLimit, creditTermDays, balancePolicy, "VND", approvedBy, now, note, 0L);
    }

    /**
     * Replace the terms. Orders already placed keep the terms they were sold on.
     *
     * @throws BusinessException {@code CREDIT_PROFILE_INVALID} when the terms contradict themselves
     */
    public void change(boolean allowPrepaid, boolean allowDeposit, boolean allowCredit, CommercialTerm defaultTerm,
                       BigDecimal depositPercent, BigDecimal creditLimit, Integer creditTermDays, UUID approvedBy,
                       Instant now, String note) {
        change(allowPrepaid, allowDeposit, allowCredit, defaultTerm, depositPercent, creditLimit, creditTermDays, null,
                approvedBy, now, note);
    }

    /**
     * @param balancePolicy null with deposits allowed keeps the policy the terms had, or BEFORE_DELIVERY - the policy
     *                      that never lets unpaid goods leave - when they had none; without deposits there is no
     *                      balance to collect, and a policy is refused
     */
    public void change(boolean allowPrepaid, boolean allowDeposit, boolean allowCredit, CommercialTerm defaultTerm,
                       BigDecimal depositPercent, BigDecimal creditLimit, Integer creditTermDays,
                       DepositBalancePolicy balancePolicy, UUID approvedBy, Instant now, String note) {
        apply(allowPrepaid, allowDeposit, allowCredit, defaultTerm, depositPercent, creditLimit, creditTermDays,
                balancePolicy, approvedBy, now, note);
    }

    private void apply(boolean prepaid, boolean deposit, boolean credit, CommercialTerm term, BigDecimal percent,
                       BigDecimal limit, Integer days, DepositBalancePolicy policy, UUID by, Instant at, String text) {
        if (!prepaid && !deposit && !credit) {
            throw invalid("At least one payment term must be allowed");
        }
        CommercialTerm chosen = term == null ? CommercialTerm.PREPAID : term;
        boolean defaultAllowed = switch (chosen) {
            case PREPAID -> prepaid;
            case DEPOSIT -> deposit;
            case CREDIT -> credit;
        };
        if (!defaultAllowed) {
            throw invalid("The default payment term %s is not one of the allowed terms".formatted(chosen));
        }
        if (deposit != (percent != null)) {
            throw invalid("A deposit percentage is given exactly when deposits are allowed");
        }
        if (percent != null && (percent.signum() <= 0 || percent.compareTo(HUNDRED) >= 0)) {
            throw invalid("The deposit percentage is above 0 and below 100");
        }
        if (!deposit && policy != null) {
            throw invalid("A balance policy is given only when deposits are allowed");
        }
        if (credit != (limit != null && days != null)) {
            throw invalid("A credit limit and the days to pay are given exactly when credit is allowed");
        }
        if (limit != null && limit.signum() < 0) {
            throw invalid("The credit limit cannot be negative");
        }
        if (days != null && (days < 0 || days > 365)) {
            throw invalid("The days to pay are between 0 and 365");
        }
        this.allowPrepaid = prepaid;
        this.allowDeposit = deposit;
        this.allowCredit = credit;
        this.defaultTerm = chosen;
        this.depositPercent = percent;
        this.creditLimit = limit;
        this.creditTermDays = days;
        this.depositBalancePolicy = !deposit ? null : policy != null ? policy
                : depositBalancePolicy != null ? depositBalancePolicy : DepositBalancePolicy.BEFORE_DELIVERY;
        this.approvedBy = Objects.requireNonNull(by, "approvedBy");
        this.approvedAt = Objects.requireNonNull(at, "approvedAt");
        this.note = text == null || text.isBlank() ? null : text.trim();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.CREDIT_PROFILE_INVALID, message);
    }

    public UUID id() { return id; }
    public UUID customerId() { return customerId; }
    public boolean allowPrepaid() { return allowPrepaid; }
    public boolean allowDeposit() { return allowDeposit; }
    public boolean allowCredit() { return allowCredit; }
    public CommercialTerm defaultTerm() { return defaultTerm; }
    public BigDecimal depositPercent() { return depositPercent; }
    public BigDecimal creditLimit() { return creditLimit; }
    public Integer creditTermDays() { return creditTermDays; }
    public DepositBalancePolicy depositBalancePolicy() { return depositBalancePolicy; }
    public String currency() { return currency; }
    public UUID approvedBy() { return approvedBy; }
    public Instant approvedAt() { return approvedAt; }
    public String note() { return note; }
    public long version() { return version; }
}
