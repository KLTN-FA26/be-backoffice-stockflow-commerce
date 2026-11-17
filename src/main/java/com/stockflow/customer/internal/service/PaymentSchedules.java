package com.stockflow.customer.internal.service;

import com.stockflow.customer.api.CustomerPaymentSchedules;
import com.stockflow.customer.api.DefaultPaymentSchedule;
import com.stockflow.customer.api.PaymentMilestone;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Setting and approving a customer's default payment schedule (kltn-docs 18 §4 steps 2 and 5, BR-03; 15 BR-12; 4B-1),
 * for the customer module's own controller. Both run under the customer's row lock - the lock the credit terms take -
 * and against the version the caller read, and are audited: each approval says who and when (18 §4 step 5).
 */
public interface PaymentSchedules extends CustomerPaymentSchedules {

    /**
     * A schedule replaced whole.
     *
     * @param approver        whether the one saving approves credit ({@code customer-payment-schedules:APPROVE}): their
     *                        AFTER_DELIVERY installments are approved as saved; anyone else's await approval
     * @param expectedVersion the version read; null (or -1, what an unset schedule reads as) only for a customer
     *                        that has none yet
     */
    record Save(UUID customerId, List<Line> installments, String note, UUID by, boolean approver,
                Long expectedVersion) {

        public Save {
            installments = installments == null ? List.of() : List.copyOf(installments);
        }
    }

    /**
     * One installment as entered, in order.
     *
     * @param dueDays days after the milestone; left out, the customer's credit term for an AFTER_DELIVERY installment
     *                (18 §4 step 5) and 0 for any other
     */
    record Line(String name, BigDecimal percent, PaymentMilestone milestone, Integer dueDays) { }

    /**
     * @throws com.stockflow.common.error.BusinessException {@code CUSTOMER_NOT_FOUND}; {@code VALIDATION_FAILED};
     *         {@code PAYMENT_SCHEDULE_NEEDS_CREDIT} for an AFTER_DELIVERY installment without the customer's approved
     *         credit; {@code OPTIMISTIC_LOCK} when it changed since {@code expectedVersion}
     */
    DefaultPaymentSchedule save(Save command);

    /**
     * Accounting approves the AFTER_DELIVERY installments of the schedule as read (15 BR-12); approving it again
     * changes nothing.
     *
     * @throws com.stockflow.common.error.BusinessException {@code CUSTOMER_NOT_FOUND}; {@code NOT_FOUND} when the
     *         customer has no schedule; {@code VALIDATION_FAILED} when nothing in it pays after delivery;
     *         {@code PAYMENT_SCHEDULE_NEEDS_CREDIT}; {@code OPTIMISTIC_LOCK}
     */
    DefaultPaymentSchedule approve(UUID customerId, long expectedVersion, UUID by);
}
