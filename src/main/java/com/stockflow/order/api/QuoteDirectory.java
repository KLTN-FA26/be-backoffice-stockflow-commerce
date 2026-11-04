package com.stockflow.order.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who a quote is for and what it prints, for a module that links its own records to a quote — the
 * production module's sample requests ({@code production.sample_request.quote_id}). Read-only and narrow:
 * no prices, no transitions. Production depends on {@code order :: api}, never the reverse, so this is
 * how it checks a quote without a cycle and without reading the {@code ordering} schema.
 */
public interface QuoteDirectory {

    /** Empty when no quote has this id. */
    Optional<QuoteReference> findQuote(UUID quoteId);

    /**
     * @param status the stored status name (DRAFT, SENT, CHANGES_REQUESTED, ACCEPTED, EXPIRED, CANCELLED,
     *               CONVERTED)
     */
    record QuoteReference(UUID quoteId, String quoteNumber, UUID customerId, String status,
                          List<QuotedDesign> designs) {
        public QuoteReference {
            designs = List.copyOf(designs);
        }
    }

    /** A design printed on a blank by one of the quote's lines. */
    record QuotedDesign(UUID designSnapshotId, String blankSku) {
    }
}
