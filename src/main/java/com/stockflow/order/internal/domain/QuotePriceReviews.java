package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port of the submissions of quotes' prices to the Sales Manager ({@code ordering.quote_price_review},
 * kltn-docs 14 BR-10): one row per submission, with what was decided on kept as it was. Joins the caller's transaction;
 * the quote's row lock serialises every write of one quote's reviews.
 */
public interface QuotePriceReviews {

    enum Outcome { PENDING, APPROVED, REJECTED, WITHDRAWN }

    /**
     * @param lines the breaks measured, as JSON ({@code QuotePriceCheck.Result#lines}); kept as decided on
     */
    record Review(UUID id, UUID quoteId, int revision, Outcome outcome, BigDecimal thresholdPercent, String lines,
                  Instant submittedAt, UUID submittedBy, Instant decidedAt, UUID decidedBy, String reason) {
    }

    /** Opens the quote's waiting review; there is at most one ({@code uk_quote_price_review_pending}). */
    Review open(UUID quoteId, int revision, BigDecimal thresholdPercent, String lines, UUID by, Instant at);

    /** The quote's review still waiting, if any. */
    Optional<Review> pending(UUID quoteId);

    /** The quote's latest review, whatever became of it. */
    Optional<Review> latest(UUID quoteId);

    /** Closes a waiting review; {@code reason} is required for {@link Outcome#REJECTED}. */
    void close(UUID reviewId, Outcome outcome, UUID by, Instant at, String reason);
}
