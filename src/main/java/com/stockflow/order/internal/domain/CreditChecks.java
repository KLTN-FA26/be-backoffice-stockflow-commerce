package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The credit side of orders (SCRUM-427, kltn-docs 15 §4.3): how much a customer owes on credit
 * orders, and the record of every credit decision ({@code ordering.order_credit_check}).
 */
public interface CreditChecks {

    /** What a credit decision says, as stored. */
    enum Outcome { WITHIN_LIMIT, OVER_LIMIT, APPROVED, REJECTED }

    /** One stored decision. */
    record Check(UUID orderId, Instant checkedAt, BigDecimal creditLimit, BigDecimal exposure, BigDecimal orderAmount,
                 Outcome outcome, UUID decidedBy, Instant decidedAt, String note) {
    }

    /**
     * What the customer owes on credit orders not yet delivered: total minus paid of every live credit
     * order, leaving out orders still waiting for a credit decision (they are not credit given yet).
     * Receivables of delivered orders are added by the payment module once they exist (SCRUM-431).
     */
    BigDecimal undeliveredCreditExposure(UUID customerId, UUID excludingOrderId);

    void record(Check check);

    Optional<Check> latest(UUID orderId);
}
