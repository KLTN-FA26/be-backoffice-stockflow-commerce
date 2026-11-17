package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a customer owes on the installments of their signed contracts, and whether any of it is overdue (kltn-docs 15
 * §4.3, BR-10). It is the whole credit exposure the order module checks (since 4B-10: the money ledger's receivables
 * and checkout's credit orders went), and blocks new credit while something is overdue; the storefront shows how much
 * and since when.
 *
 * @param outstandingReceivables what the installments paid after delivery still owe, not yet due included, and the
 *                               cancellation fee items not paid (15 BR-10) - the name is the money ledger's
 * @param overdueAmount        what is still unpaid on installments past their due date; zero when none
 * @param oldestOverdueDueDate the earliest due date among them; null when nothing is overdue
 */
public record CreditPosition(BigDecimal outstandingReceivables, boolean hasOverdue, BigDecimal overdueAmount,
                             LocalDate oldestOverdueDueDate) {

    /** Without the overdue detail: what is overdue is not known beyond the flag. */
    public CreditPosition(BigDecimal outstandingReceivables, boolean hasOverdue) {
        this(outstandingReceivables, hasOverdue, BigDecimal.ZERO, null);
    }
}
