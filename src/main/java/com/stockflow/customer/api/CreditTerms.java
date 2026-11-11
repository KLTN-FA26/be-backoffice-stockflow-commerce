package com.stockflow.customer.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer's credit (kltn-docs 18 §3, 15 BR-12): the credit limit and the days to pay after delivery, as accounting
 * approved them. Since 4B-10 nothing more: how a customer pays is the sales contract's installment plan (15 BR-01), not
 * payment terms chosen per order, so the allowed terms, the default term, the deposit share and the deposit balance
 * policy went with the money ledger.
 *
 * @param configured     false for a customer nobody has set credit for: no credit (a limit of zero)
 * @param creditLimit    null without credit
 * @param creditTermDays the days to pay after delivery; null exactly when the limit is
 */
public record CreditTerms(
        UUID customerId,
        boolean configured,
        BigDecimal creditLimit,
        Integer creditTermDays,
        String currency,
        UUID approvedBy,
        Instant approvedAt,
        String note,
        long version
) {

    /** No credit set: nothing may be owed after delivery. */
    public static CreditTerms none(UUID customerId) {
        return new CreditTerms(customerId, false, null, null, "VND", null, null, null, -1L);
    }
}
