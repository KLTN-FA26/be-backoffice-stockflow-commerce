package com.stockflow.order.internal.repository;

import com.stockflow.order.internal.domain.QuoteStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read side of quotes: the list and the history of one quote. */
public interface QuoteSearch {

    /**
     * @param customerId          forced to the caller's own customer for a portal user
     * @param seenByCustomerOnly  true for a portal user: drafts and quotes cancelled before sending are
     *                            Sales' working copies and are not shown; one cancelled after it was sent is
     * @param now                 the moment the statuses are read at: a SENT quote whose validity ended by then is
     *                            EXPIRED for the filter, as every read reports it, before the sweep writes it down
     */
    record Criteria(UUID customerId, List<QuoteStatus> statuses, String number, boolean seenByCustomerOnly,
                    Instant now) {
    }

    record Row(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
               Instant validUntil, Instant sentAt, UUID convertedOrderId,
               Instant createdAt, String createdBy, long version) {
    }

    record Transition(int revision, QuoteStatus fromStatus, QuoteStatus toStatus, String reason, String offer,
                      Instant changedAt, UUID changedBy) {
    }

    Page<Row> search(Criteria criteria, Pageable pageable);

    /** Oldest first. */
    List<Transition> history(UUID quoteId);

    /** {@link #history} of each of {@code quoteIds} in one statement; a quote with no history is not a key. */
    java.util.Map<UUID, List<Transition>> histories(java.util.Collection<UUID> quoteIds);

    /** Audit fields of one quote, for the detail view. */
    java.util.Optional<Row> findRow(UUID quoteId);

    /**
     * The validity of the offer each quote was last sent with, as its latest SENT history row kept it - what a quote
     * Sales are revising is listed with to its customer. One statement for all of them; a quote never sent with an offer
     * on its history is not a key.
     */
    java.util.Map<UUID, Instant> lastSentValidUntil(java.util.Collection<UUID> quoteIds);

    /** The quote an order was converted from; empty for an order no quote became. */
    java.util.Optional<UUID> quoteConvertedInto(UUID orderId);

    /**
     * A quote by the request id it was created under; {@code sentAt} is when it was first sent, null before;
     * {@code status} its {@code QuoteStatus} name.
     */
    record ByRequest(UUID requestId, UUID quoteId, String quoteNumber, Instant sentAt, String status) {
    }

    /** The quotes created under any of {@code requestIds}, one statement ({@code uk} on request_id: one each). */
    List<ByRequest> byRequestIds(java.util.Collection<UUID> requestIds);
}
