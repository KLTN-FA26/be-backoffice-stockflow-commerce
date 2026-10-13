package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when a customer's step on a quote hands the next one to Sales: the
 * customer accepted it (Sales converts it), asked for changes (Sales revises it), or reordered a negotiated
 * order (Sales prices the new draft, user decision P1 of 2026-10-12). The notification module tells Sales.
 *
 * <p>The recipient is resolved by the publisher, which knows who handled the quote: the Sales account that
 * sent it - for a reorder, the one that sent the order's quote. {@code salesEmail} is null when that account
 * no longer exists, is not active, or has no e-mail; the listener then hands the notice to every active Sales
 * account to take over (user decision N1, 2026-10-13).</p>
 *
 * @param detail the customer's feedback for {@code CHANGES_REQUESTED}, the reordered order's number for
 *               {@code REORDER_REQUESTED}, null for {@code ACCEPTED}
 */
public record CustomerActedOnQuote(
        UUID quoteId,
        String quoteNumber,
        UUID customerId,
        String customerName,
        Action action,
        String detail,
        UUID salesUserId,
        String salesEmail,
        Instant occurredAt
) {

    public enum Action { ACCEPTED, CHANGES_REQUESTED, REORDER_REQUESTED }
}
