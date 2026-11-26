package com.stockflow.order.internal.service;

import java.util.UUID;

/**
 * 14 BR-10: the Sales Manager approved or rejected a quote's prices. Within the order module only - it tells the Sales
 * account that asked ({@link QuotePriceReviewNotices}), after commit.
 *
 * @param outcome {@code APPROVED} or {@code REJECTED}
 * @param reason  why it was rejected, or the approver's note; null when none
 */
public record QuotePriceReviewDecided(UUID quoteId, String quoteNumber, int revision, UUID reviewId, String outcome,
                                      UUID submittedBy, UUID decidedBy, String reason) {
}
