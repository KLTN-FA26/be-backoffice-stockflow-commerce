package com.stockflow.order.internal.service;

import java.util.UUID;

/**
 * 14 BR-10: a quote waits for the Sales Manager to approve its prices. Within the order module only - it tells the
 * approvers in the in-app inbox ({@link QuotePriceReviewNotices}), after commit. Ids and counts only.
 *
 * @param linesToReview how many lines have a break that needs it
 */
public record QuotePriceReviewRequested(UUID quoteId, String quoteNumber, int revision, UUID reviewId,
                                        UUID submittedBy, int linesToReview) {
}
