package com.stockflow.order.internal.controller.dto;

import com.stockflow.customer.api.CustomerLabel;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A contract not sent because its credit check failed, waiting for accounting (kltn-docs 15 BR-11, 2026-11-26), with
 * the figures as they were when Sales sent it.
 *
 * @param contractVersion the contract's version now: what an approval or a refusal is sent with
 * @param customer        the customer's name and code, as on the credit holds; null for one the customer module lost
 * @param checkOutcome    OVERDUE or OVER_LIMIT
 */
@Schema(name = "ContractCreditReview")
public record ContractCreditReviewResponse(UUID reviewId, UUID orderId, String orderNumber, UUID contractId,
                                           String contractNumber, long contractVersion, UUID customerId,
                                           CustomerLabel customer, String checkOutcome, BigDecimal creditLimit,
                                           BigDecimal exposure, BigDecimal afterDelivery, BigDecimal overdueAmount,
                                           UUID submittedBy, Instant submittedAt) {
}
