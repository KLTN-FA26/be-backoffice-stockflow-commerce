package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>payment</b> module when the accountant approved or rejected a credit request (QĐ-A, 2026-11-26;
 * kltn-docs 15 §4.3). Approved, the customer's credit profile was written in the same transaction. The notification
 * module tells the customer and the Sales who filed it or gave their opinion on it.
 *
 * <p>Ids, amounts and times only - not the rejection's reason, which the accountant typed: the customer and Sales read
 * it on the request. See {@code package-info.java} for the rules on changing this contract.</p>
 *
 * <p>The customer is told in their portal account's inbox, which the listener looks up when it delivers.</p>
 *
 * @param submittedBy   who filed it: the customer's portal account, or a Sales account
 * @param recommendedBy  who gave Sales' opinion on it
 * @param approvedLimit  the limit granted; null when rejected
 * @param approvedTermDays the days to pay after delivery granted; null when rejected
 */
public record CreditRequestDecided(
        UUID creditRequestId,
        UUID customerId,
        Outcome outcome,
        BigDecimal approvedLimit,
        Integer approvedTermDays,
        String currency,
        UUID submittedBy,
        UUID recommendedBy,
        UUID decidedBy,
        Instant decidedAt
) {

    public enum Outcome { APPROVED, REJECTED }
}
