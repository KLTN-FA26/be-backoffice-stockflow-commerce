package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module each time a quote moves (kltn-docs 14 §5, 03-state-machines §18): sent, accepted,
 * sent back for changes, reopened, expired, cancelled, converted into its order. The chat follows it with the quote's
 * card (16 §4.3 step 7, BR-03: "the card shows the result wherever the customer acted"), posting a new card when a
 * revision is sent. {@link CustomerActedOnQuote} stays the notice to Sales; this is the quote's whole lifecycle.
 *
 * <p>Facts only: the customer's feedback and Sales' reasons stay on the quote. Consumers receive it in-process; see
 * {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param fromStatus the order module's name of the status it left; null when it was just drafted
 * @param toStatus   the status it is in now, e.g. {@code SENT}, {@code ACCEPTED}, {@code EXPIRED}
 * @param changedBy  who moved it: the Sales account, the customer's portal account; null when the system did (expiry)
 */
public record QuoteStatusChanged(UUID quoteId, String quoteNumber, UUID customerId, int revision, String fromStatus,
                                 String toStatus, UUID changedBy, Instant occurredAt) {
}
