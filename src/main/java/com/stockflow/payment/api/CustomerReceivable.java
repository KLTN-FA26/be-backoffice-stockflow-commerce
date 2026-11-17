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
 * <p>{@code signingOverdue} stands apart from all of that. An installment due on signing is not a receivable in 15 §4.3
 * (it is paid before the goods leave), so it is in no bucket, in no total above and not in the exposure; but the credit
 * check of a new contract ({@code SalesContractService.creditCheck}, reading {@link CreditPosition#hasOverdue}) calls a
 * customer overdue for an installment of any milestone past its due day, the one on signing included. Shown as its own
 * figure, a customer the contract check calls overdue never looks clean on the report. Installments before delivery
 * never have a due day ({@code ck_installment_due_date}), so with it the report shows every amount the credit check
 * calls overdue: {@code overdue() + signingOverdue}.</p>
 *
 * @param receivable          the installments paid after delivery whose milestone was reached (the goods delivered),
 *                            and the fee items (a cancellation's, a failed trip's) not paid in full
 * @param committedNotReached the installments paid after delivery of signed contracts whose goods are not delivered
 *                            yet: owed under the contract, not yet receivable, without a due day
 * @param signingOverdue      what is still owed on installments due on signing whose due day is before today, not PAID
 *                            and not CANCELLED; outside {@code receivable} and the exposure
 * @param oldestDueDate       the earliest due day among the receivable items; null when there is none
 * @param openItems           how many receivable items are still owing
 */
public record CustomerReceivable(UUID customerId, String currency, BigDecimal receivable, BigDecimal notYetDue,
                                 BigDecimal overdue1To30, BigDecimal overdue31To60, BigDecimal overdue61To90,
                                 BigDecimal overdueOver90, BigDecimal committedNotReached, BigDecimal signingOverdue,
                                 LocalDate oldestDueDate, int openItems) {

    /** Everything receivable that is overdue, whatever its age (15 §4.3: {@code signingOverdue} is not in it). */
    public BigDecimal overdue() {
        return overdue1To30.add(overdue31To60).add(overdue61To90).add(overdueOver90);
    }
}
