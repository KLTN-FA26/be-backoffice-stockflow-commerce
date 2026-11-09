package com.stockflow.customer.api;

import java.util.UUID;

/**
 * Customers' default payment schedules (kltn-docs 18 §3, §4 steps 2 and 5, BR-03; 14 §4.3 step 12; team plan 4B-1): what
 * a new sales contract's payment schedule starts from. Read only here: Sales and accounting set and approve it through
 * the customer module's own endpoints, where who may approve is decided from the caller's grants - never by another
 * module.
 */
public interface CustomerPaymentSchedules {

    /**
     * The customer's default schedule; when none was set, the one their commercial terms give
     * ({@link DefaultPaymentSchedule#configured()}).
     *
     * @throws com.stockflow.common.error.BusinessException {@code CUSTOMER_NOT_FOUND}
     */
    DefaultPaymentSchedule defaultPaymentSchedule(UUID customerId);
}
