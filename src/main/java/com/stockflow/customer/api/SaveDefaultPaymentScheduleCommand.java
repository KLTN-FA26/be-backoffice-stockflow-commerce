package com.stockflow.customer.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * A customer's default payment schedule, replaced whole (kltn-docs 18 §4 steps 2 and 5).
 *
 * @param approver        whether the one saving approves credit (an accountant, 18 BR-03): required for an
 *                        AFTER_DELIVERY instalment, and recorded as its approval
 * @param expectedVersion the version read; null only for a customer that has none yet
 */
public record SaveDefaultPaymentScheduleCommand(UUID customerId, List<Line> installments, String note, UUID by,
                                                boolean approver, Long expectedVersion) {

    /** One instalment as entered, in order. */
    public record Line(String name, BigDecimal percent, PaymentMilestone milestone, Integer dueDays) { }

    public SaveDefaultPaymentScheduleCommand {
        installments = installments == null ? List.of() : List.copyOf(installments);
    }
}
