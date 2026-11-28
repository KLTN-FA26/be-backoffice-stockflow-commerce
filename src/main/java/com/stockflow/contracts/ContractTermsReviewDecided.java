package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by <b>order</b> when a Sales Manager approved or rejected a draft contract's terms. The account that asked is
 * told, and the salesperson who looks after the customer when that is someone else.
 *
 * @param outcome {@code APPROVED} or {@code REJECTED}
 */
public record ContractTermsReviewDecided(UUID orderId, UUID contractId, int revision, String contractNumber,
                                         String orderNumber, UUID customerId, String outcome, UUID requestedBy,
                                         UUID decidedBy, Instant occurredAt) {
}
