package com.stockflow.customer.internal.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Port for who has been in touch with a customer lately (sales-owner plan §10.5 step 3): the managers' warning that the
 * salesperson a customer asks for may have canvassed them. Fed from the quotes and contracts sent to the customer
 * ({@code customer.customer_sales_contact}) and read together with the owner history (who had the customer, and so its
 * conversations).
 */
public interface SalesContacts {

    /** What brought the salesperson in touch. */
    enum Source { QUOTE, CONTRACT }

    /** A salesperson and a customer. */
    record Pair(UUID customerId, UUID userId) {
    }

    /** Keeps the later of the stored time and {@code at}; joins the caller's transaction. */
    void record(UUID customerId, UUID userId, Source source, Instant at);

    /**
     * Of {@code pairs}, those where the salesperson sent the customer a quote or a contract at or after {@code since},
     * or owned the customer at some time since then (assigned to them, or moved away from them, within the window). One
     * query whatever the number of pairs.
     */
    Set<Pair> inTouchSince(Collection<Pair> pairs, Instant since);
}
