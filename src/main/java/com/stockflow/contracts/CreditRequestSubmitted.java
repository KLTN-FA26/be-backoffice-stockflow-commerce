package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>payment</b> module when a credit request waits for someone (QĐ-A, 2026-11-26; kltn-docs 15
 * §4.3): the customer filed one from the portal and it waits for Sales' opinion ({@link Stage#AWAITING_SALES}), or
 * Sales filed one for the customer, or gave their opinion on the customer's, and it waits for the accountant
 * ({@link Stage#AWAITING_DECISION}). The notification module tells whoever acts next: Sales, or the accountant.
 *
 * <p>Ids, amounts and times only: the event is kept in {@code event_publication}, so it carries no name, no address
 * and nothing anybody typed - not the reason, not Sales' opinion. Whoever is told opens the request to read them.</p>
 *
 * See {@code package-info.java} for the rules on changing this contract.
 *
 * @param submittedBy who filed it, or gave Sales' opinion for {@link Stage#AWAITING_DECISION} after the customer filed
 */
public record CreditRequestSubmitted(
        UUID creditRequestId,
        UUID customerId,
        Stage stage,
        BigDecimal requestedLimit,
        int requestedTermDays,
        String currency,
        UUID submittedBy,
        Instant occurredAt
) {

    public enum Stage { AWAITING_SALES, AWAITING_DECISION }
}
