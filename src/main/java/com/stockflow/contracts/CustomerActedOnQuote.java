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
 * <p>Facts only (verify.py rule 16, inbox rule of develop #102): what the customer typed when asking for changes
 * stays on the quote, behind its permission; Sales opens the quote to read it.</p>
 *
 * @param sourceOrderNumber the reordered order's number for {@code REORDER_REQUESTED}, null otherwise
 */
public record CustomerActedOnQuote(
        UUID quoteId,
        String quoteNumber,
        UUID customerId,
        String customerName,
        Action action,
        String sourceOrderNumber,
        UUID salesUserId,
        String salesEmail,
        Instant occurredAt
) {

    public enum Action { ACCEPTED, CHANGES_REQUESTED, REORDER_REQUESTED }
}
