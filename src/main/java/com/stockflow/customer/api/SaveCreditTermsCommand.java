package com.stockflow.customer.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Set a customer's commercial terms; only whoever approves credit may (kltn-docs 18 BR-02).
 *
 * @param expectedVersion      the version the caller read, or null for a customer with no terms yet
 * @param depositBalancePolicy how a deposit order's balance is collected (G2-3); null with deposits allowed keeps the
 *                             policy the terms had, or BEFORE_DELIVERY (goods never leave unpaid) when they had none
 */
public record SaveCreditTermsCommand(
        UUID customerId,
        boolean allowPrepaid,
        boolean allowDeposit,
        boolean allowCredit,
        CommercialTerm defaultTerm,
        BigDecimal depositPercent,
        BigDecimal creditLimit,
        Integer creditTermDays,
        String note,
        UUID approvedBy,
        Long expectedVersion,
        DepositBalancePolicy depositBalancePolicy
) {

    /** Before G2-3: the balance policy defaults. */
    public SaveCreditTermsCommand(UUID customerId, boolean allowPrepaid, boolean allowDeposit, boolean allowCredit,
                                  CommercialTerm defaultTerm, BigDecimal depositPercent, BigDecimal creditLimit,
                                  Integer creditTermDays, String note, UUID approvedBy, Long expectedVersion) {
        this(customerId, allowPrepaid, allowDeposit, allowCredit, defaultTerm, depositPercent, creditLimit,
                creditTermDays, note, approvedBy, expectedVersion, null);
    }
}
