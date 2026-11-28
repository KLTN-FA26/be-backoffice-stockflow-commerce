package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by <b>order</b> when Sales puts a draft contract that deviates from the standard terms in front of a Sales
 * Manager (QĐ-4 of the contract e-signing plan). The holders of {@code sales-contract-terms:APPROVE} are told, the
 * account that asked excepted.
 */
public record ContractTermsReviewRequested(UUID orderId, UUID contractId, int revision, String contractNumber,
                                           String orderNumber, UUID requestedBy, Instant occurredAt) {
}
