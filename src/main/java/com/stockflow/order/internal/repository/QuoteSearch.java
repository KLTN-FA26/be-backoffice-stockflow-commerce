package com.stockflow.order.internal.repository;

import com.stockflow.order.api.PaymentTerm;
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
     *                            Sales' working copies and are not shown
     */
    record Criteria(UUID customerId, List<QuoteStatus> statuses, String number, boolean seenByCustomerOnly) {
    }

    record Row(UUID id, String quoteNumber, UUID customerId, QuoteStatus status, int revision,
               PaymentTerm paymentTerm, Instant validUntil, Instant sentAt, UUID convertedOrderId,
               Instant createdAt, String createdBy, long version) {
    }

    record Transition(int revision, QuoteStatus fromStatus, QuoteStatus toStatus, String reason, String offer,
                      Instant changedAt, UUID changedBy) {
    }

    Page<Row> search(Criteria criteria, Pageable pageable);

    /** Oldest first. */
    List<Transition> history(UUID quoteId);

    /** Audit fields of one quote, for the detail view. */
    java.util.Optional<Row> findRow(UUID quoteId);
}
