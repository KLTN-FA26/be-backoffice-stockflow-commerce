package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when a customer's step on a quote hands the next one to Sales: the
 * customer accepted it (Sales converts it), asked for changes (Sales revises it), or reordered a negotiated
 * order (Sales prices the new draft, user decision P1 of 2026-10-12). The notification module tells Sales.
 *
 * <p>The publisher names who handled the quote: the Sales account that sent it - for a reorder, the one that sent
 * the order's quote. The listener resolves that account itself when it delivers; when it no longer exists, is not
 * active, or has no e-mail, the notice goes to every active Sales account to take over (user decision N1,
 * 2026-10-13).</p>
 *
 * <p>Ids and document numbers only: the event is stored in {@code event_publication} until delivered and kept
 * there afterwards, so it carries no name, no e-mail address (review of #109, item 10; verify.py rule 16b) and
 * nothing the customer typed (verify.py rule 16, inbox rule of develop #102). Sales opens the quote to read who and
 * what.</p>
 *
 * @param sourceOrderNumber the reordered order's number for {@code REORDER_REQUESTED}, null otherwise
 */
public record CustomerActedOnQuote(
        UUID quoteId,
        String quoteNumber,
        UUID customerId,
        Action action,
        String sourceOrderNumber,
        UUID salesUserId,
        Instant occurredAt
) {

    public enum Action { ACCEPTED, CHANGES_REQUESTED, REORDER_REQUESTED }
}
