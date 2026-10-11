package com.stockflow.customer.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Set a customer's commercial terms; only whoever approves credit may (kltn-docs 18 BR-02).
 *
 * @param expectedVersion the version the caller read, or null for a customer with no terms yet
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
        Long expectedVersion
) {
}
