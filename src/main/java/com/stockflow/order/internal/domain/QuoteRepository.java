package com.stockflow.order.internal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port of quotes ({@code ordering.quote} and its lines, tiers and history). */
public interface QuoteRepository {

    Optional<Quote> findById(UUID id);

    /**
     * Loads the quote and locks its row until the transaction ends: the customer accepting while Sales
     * edits, or two conversions at once, are serialised here, and the second sees the first's result.
     */
    Optional<Quote> findByIdForUpdate(UUID id);

    Optional<Quote> findByRequestId(UUID requestId);

    /** The quote an order was converted from; empty for an order placed at the price list. */
    Optional<Quote> findByConvertedOrderId(UUID orderId);

    /**
     * Serialises creators of the same request id for the rest of the transaction, so a retried
     * "create" finds the first one's quote instead of racing it into the unique index.
     */
    void lockRequest(UUID requestId);

    /** Inserts or writes the quote, its lines and tiers; flushed, so a constraint fails here. */
    Quote save(Quote quote);

    /**
     * One history row per transition. {@code offer} is the commercial content as sent, kept as JSON on
     * the SENT rows so later revisions cannot rewrite what the customer saw.
     */
    void recordTransition(UUID quoteId, int revision, QuoteStatus from, QuoteStatus to, String reason,
                          String offer, Instant at, UUID by);

    /** Sent quotes whose validity has passed, oldest first, at most {@code limit}. */
    List<UUID> sentAndExpiredBy(Instant now, int limit);

    /** {@code QT-yyyyMMdd-nnnn} from {@code platform.document_sequence}, in the caller's transaction. */
    String nextNumber(LocalDate day);
}
