package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The salesperson who last dealt with a customer through a document, derived on read (owner decision 2026-11-27).
 * Nothing stores a salesperson on the customer: kltn-docs 00 §2 (decided 2026-11-25) makes the salesperson responsible
 * a property of a document - whoever sent it (a quote: who sent the quote; a contract or an order: who sent the contract). The back
 * office customer list still wants one name per customer, so it shows the sender of the customer's most recent document.
 */
public interface SalesContacts {

    /** The kind of document the salesperson is named from. */
    enum Source { QUOTE, CONTRACT }

    /**
     * A customer's most recent sent document and who answers for it.
     *
     * @param salesUserId who sent it; for a contract with no sender recorded, who recorded it signed - the reading
     *                    {@code SalesRecipients} gives a contract's responsibility
     * @param at          when it was sent (or, in that fallback, recorded signed)
     */
    record Latest(UUID customerId, Source source, String documentNumber, UUID salesUserId, Instant at) { }

    /**
     * The latest document of each customer that has one, in one statement: customers with no sent quote and no contract
     * sent or signed are absent.
     */
    Map<UUID, Latest> latestOf(Collection<UUID> customerIds);
}
