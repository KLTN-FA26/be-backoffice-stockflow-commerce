package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What one customer owes after delivery, aged by due date, on the day asked (kltn-docs 15 §4.3 step 8; 2026-11-14 plan,
 * BE2-B). Every amount is what is still to be paid - amount less paid less what a closure or a return reduced - in
 * {@code currency}.
 *
 * <p>{@code receivable} is the sum of the five age buckets: {@code notYetDue} (due today or later) and overdue by 1-30,
 * 31-60, 61-90 or more than 90 days after the due day. {@code receivable + committedNotReached} is the customer's whole
 * credit exposure, the {@link CreditPosition#outstandingReceivables} the contract's credit check counts (15 BR-10).</p>
 *
 * @param receivable          the installments paid after delivery whose milestone was reached (the goods delivered),
 *                            and the fee items (a cancellation's, a failed trip's) not paid in full
 * @param committedNotReached the installments paid after delivery of signed contracts whose goods are not delivered
 *                            yet: owed under the contract, not yet receivable, without a due day
 * @param oldestDueDate       the earliest due day among the receivable items; null when there is none
 * @param openItems           how many receivable items are still owing
 */
public record CustomerReceivable(UUID customerId, String currency, BigDecimal receivable, BigDecimal notYetDue,
                                 BigDecimal overdue1To30, BigDecimal overdue31To60, BigDecimal overdue61To90,
                                 BigDecimal overdueOver90, BigDecimal committedNotReached, LocalDate oldestDueDate,
                                 int openItems) {

    /** Everything overdue, whatever its age. */
    public BigDecimal overdue() {
        return overdue1To30.add(overdue31To60).add(overdue61To90).add(overdueOver90);
    }
}
