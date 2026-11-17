package com.stockflow.customer.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Set a customer's credit; only whoever approves credit may (kltn-docs 18 BR-02, 15 BR-12).
 *
 * @param creditLimit     null with {@code creditTermDays} null: no credit
 * @param expectedVersion the version the caller read; null only for a customer with no terms yet (-1, the version of
 *                        unset terms, is accepted too). A customer that has terms is refused without it (SCRUM-484)
 */
public record SaveCreditTermsCommand(
        UUID customerId,
        BigDecimal creditLimit,
        Integer creditTermDays,
        String note,
        UUID approvedBy,
        Long expectedVersion
) {
}
