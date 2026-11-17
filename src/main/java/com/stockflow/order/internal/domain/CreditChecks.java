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

    /** One stored decision. */
    record Check(UUID orderId, Instant checkedAt, BigDecimal creditLimit, BigDecimal exposure, BigDecimal orderAmount,
                 Outcome outcome, UUID decidedBy, Instant decidedAt, String note) {
    }

    void record(Check check);

    Optional<Check> latest(UUID orderId);
}
