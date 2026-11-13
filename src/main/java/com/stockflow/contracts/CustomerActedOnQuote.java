package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when a customer's step on a quote hands the next one to Sales: the
 * customer accepted it (Sales converts it) or asked for changes (Sales revises it). The notification module tells
 * Sales. A customer no longer reorders through a quote: "order again" opens a chat with Sales (kltn-docs 13 BR-08,
 * team plan 5-2), so the reorder action and the reordered order's number it carried are gone.
 *
 * <p>The publisher names who handled the quote: the Sales account that sent it. The listener resolves that account
 * itself when it delivers; when it no longer exists, is not active, or has no e-mail, the notice goes to every active
 * Sales account to take over (user decision N1, 2026-10-13).</p>
 *
 * <p>Ids and document numbers only: the event is stored in {@code event_publication} until delivered and kept
 * there afterwards, so it carries no name, no e-mail address (review of #109, item 10; verify.py rule 16b) and
 * nothing the customer typed (verify.py rule 16, inbox rule of develop #102). Sales opens the quote to read who and
 * what.</p>
 */
public record CustomerActedOnQuote(
        UUID quoteId,
        String quoteNumber,
        UUID customerId,
        Action action,
        UUID salesUserId,
        Instant occurredAt
) {

    public enum Action { ACCEPTED, CHANGES_REQUESTED }
}
