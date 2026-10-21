package com.stockflow.customer.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer's commercial terms (kltn-docs 18 §3, 15 §3): the payment terms allowed, the default
 * deposit percentage, the credit limit and the days to pay a credit order.
 *
 * @param configured           false for a customer nobody has set terms for: prepaid only (SCRUM-427)
 * @param depositBalancePolicy how a deposit order's balance is collected (G2-3); set exactly when deposits are allowed
 */
public record CreditTerms(
        UUID customerId,
        boolean configured,
        boolean allowPrepaid,
        boolean allowDeposit,
        boolean allowCredit,
        CommercialTerm defaultTerm,
        BigDecimal depositPercent,
        BigDecimal creditLimit,
        Integer creditTermDays,
        String currency,
        UUID approvedBy,
        Instant approvedAt,
        String note,
        long version,
        DepositBalancePolicy depositBalancePolicy
) {

    /** Before G2-3: a deposit customer pays the balance before delivery. */
    public CreditTerms(UUID customerId, boolean configured, boolean allowPrepaid, boolean allowDeposit,
                       boolean allowCredit, CommercialTerm defaultTerm, BigDecimal depositPercent,
                       BigDecimal creditLimit, Integer creditTermDays, String currency, UUID approvedBy,
                       Instant approvedAt, String note, long version) {
        this(customerId, configured, allowPrepaid, allowDeposit, allowCredit, defaultTerm, depositPercent, creditLimit,
                creditTermDays, currency, approvedBy, approvedAt, note, version,
                allowDeposit ? DepositBalancePolicy.BEFORE_DELIVERY : null);
    }

    /** No terms set: the customer prepays. */
    public static CreditTerms prepaidOnly(UUID customerId) {
        return new CreditTerms(customerId, false, true, false, false, CommercialTerm.PREPAID, null, null, null, "VND",
                null, null, null, 0L, null);
    }

    public boolean allows(CommercialTerm term) {
        return switch (term) {
            case PREPAID -> allowPrepaid;
            case DEPOSIT -> allowDeposit;
            case CREDIT -> allowCredit;
        };
    }
}
