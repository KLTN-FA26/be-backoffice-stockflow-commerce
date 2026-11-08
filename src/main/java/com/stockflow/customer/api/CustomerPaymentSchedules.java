package com.stockflow.customer.api;

import java.util.UUID;

/**
 * Customers' default payment schedules (kltn-docs 18 §3, §4 steps 2 and 5, BR-03; 14 §4.3 step 12; team plan 4B-1): what
 * a new sales contract's payment schedule starts from.
 */
public interface CustomerPaymentSchedules {

    /**
     * The customer's default schedule; a single ON_SIGNING instalment of 100% when none was set.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CUSTOMER_NOT_FOUND}
     */
    DefaultPaymentSchedule defaultPaymentSchedule(UUID customerId);

    /**
     * Replaces the customer's default schedule, under the customer's row lock (as the credit terms are, so a limit
     * lowered meanwhile is seen). Sales sets ON_SIGNING and BEFORE_DELIVERY instalments; an AFTER_DELIVERY one needs an
     * approver and the customer's approved credit (18 BR-03), and is recorded as approved by them.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CUSTOMER_NOT_FOUND}; {@code VALIDATION_FAILED};
     *         {@code FORBIDDEN} for an AFTER_DELIVERY instalment without approval; {@code PAYMENT_SCHEDULE_NEEDS_CREDIT};
     *         {@code OPTIMISTIC_LOCK} when it changed since {@code expectedVersion}
     */
    DefaultPaymentSchedule save(SaveDefaultPaymentScheduleCommand command);
}
