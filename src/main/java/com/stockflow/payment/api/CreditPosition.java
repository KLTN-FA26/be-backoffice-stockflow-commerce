package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a customer owes on delivered credit orders, and whether any of it is overdue (kltn-docs 15
 * §4.3; SCRUM-431, SCRUM-193). The order module adds it to the credit exposure and blocks new credit while
 * something is overdue; the storefront shows how much and since when.
 *
 * @param overdueAmount        what is still unpaid on receivables past their due date; zero when none
 * @param oldestOverdueDueDate the earliest due date among them; null when nothing is overdue
 */
public record CreditPosition(BigDecimal outstandingReceivables, boolean hasOverdue, BigDecimal overdueAmount,
                             LocalDate oldestOverdueDueDate) {

    /** Without the overdue detail: what is overdue is not known beyond the flag. */
    public CreditPosition(BigDecimal outstandingReceivables, boolean hasOverdue) {
        this(outstandingReceivables, hasOverdue, BigDecimal.ZERO, null);
    }
}
