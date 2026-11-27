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
     * @param priceReviewPendingOnly 14 BR-10: only drafts waiting for the Sales Manager to approve their prices
     */
    record Criteria(UUID customerId, List<QuoteStatus> statuses, String number, boolean seenByCustomerOnly,
                    Instant now, boolean priceReviewPendingOnly) {

        public Criteria(UUID customerId, List<QuoteStatus> statuses, String number, boolean seenByCustomerOnly,
                        Instant now) {
            this(customerId, statuses, number, seenByCustomerOnly, now, false);
        }
    }

    /** @param priceReviewPending 14 BR-10: the draft waits for the Sales Manager to approve its prices */
    record Row(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
               Instant validUntil, Instant sentAt, UUID convertedOrderId,
               Instant createdAt, String createdBy, long version, boolean priceReviewPending) {

        public Row(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
                   Instant validUntil, Instant sentAt, UUID convertedOrderId, Instant createdAt, String createdBy,
                   long version) {
            this(id, quoteNumber, customerId, status, revision, validUntil, sentAt, convertedOrderId, createdAt,
                    createdBy, version, false);
        }
    }

    record Transition(int revision, QuoteStatus fromStatus, QuoteStatus toStatus, String reason, String offer,
                      Instant changedAt, UUID changedBy) {
    }

    Page<Row> search(Criteria criteria, Pageable pageable);

    /**
     * The quotes of one stored status that {@link #search} would find, counted.
     *
     * @param total              all of them
     * @param pastValidity       of those, SENT and past their validity at {@link Criteria#now}: read as EXPIRED
     * @param sent               of those, sent at least once ({@code sent_at} set): a DRAFT among them is a revision being
     *                           written, which the customer reads as CHANGES_REQUESTED
     * @param priceReviewPending of those, waiting for the Sales Manager to approve their prices (14 BR-10)
     */
    record StatusCount(QuoteStatus status, long total, long pastValidity, long sent, long priceReviewPending) {
    }

    /**
     * {@link #search}'s quotes under the same criteria, counted per stored status in one grouped statement; a status
     * with none is absent. The caller reads each as its list does.
     */
    List<StatusCount> statusCounts(Criteria criteria);

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

    /** The number and status of an order a quote was converted into. */
    record ConvertedOrderRef(UUID orderId, String orderNumber, String status) {
    }

    /**
     * The number and status of each of {@code orderIds}, in one statement: a page of quotes names the order each became
     * without a read per row. An unknown id is not a key.
     */
    java.util.Map<UUID, ConvertedOrderRef> convertedOrders(java.util.Collection<UUID> orderIds);

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
