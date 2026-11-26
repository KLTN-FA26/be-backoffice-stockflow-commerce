package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The record of every credit decision on an order (SCRUM-427, kltn-docs 15 §4.3 step 10:
 * {@code ordering.order_credit_check}). What a customer owes is payment's ({@code PaymentService.creditPosition}); until
 * 4B-10 the undelivered credit orders of checkout were counted here.
 */
public interface CreditChecks {

    /** What a credit decision says, as stored. */
    enum Outcome { WITHIN_LIMIT, OVER_LIMIT, OVERDUE, APPROVED, REJECTED }

    /**
     * One stored decision.
     *
     * @param preSendReviewId the approval accounting gave before the contract was sent that let this failed check at
     *                        signing through (15 BR-11, decision 2 of the credit plan, 2026-11-26); null otherwise
     */
    record Check(UUID orderId, Instant checkedAt, BigDecimal creditLimit, BigDecimal exposure, BigDecimal orderAmount,
                 Outcome outcome, UUID decidedBy, Instant decidedAt, String note, UUID preSendReviewId) {

        /** A check no pre-send approval let through. */
        public Check(UUID orderId, Instant checkedAt, BigDecimal creditLimit, BigDecimal exposure,
                     BigDecimal orderAmount, Outcome outcome, UUID decidedBy, Instant decidedAt, String note) {
            this(orderId, checkedAt, creditLimit, exposure, orderAmount, outcome, decidedBy, decidedAt, note, null);
        }
    }

    void record(Check check);

    Optional<Check> latest(UUID orderId);
}
