package com.stockflow.reporting.internal.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One customer's row of the receivables report (kltn-docs 15 §3, §4.3; 2026-11-14 plan, BE2-B): what they owe after
 * delivery, aged by due date, beside the credit accounting approved for them.
 *
 * <p>The exposure is what the credit check of a new contract counts (15 BR-10): the receivable and the installments
 * after delivery of signed contracts whose goods have not left yet. The credit left is the limit less that exposure,
 * never below zero, as the customer's own credit position shows it ({@code CreditHoldService.position}); null for a
 * customer without approved credit, who has no limit to have anything left of.</p>
 *
 * @param creditLimit    null for a customer accounting set no credit for
 * @param creditTermDays the days to pay after delivery; null exactly when the limit is
 */
public record ReceivableLine(UUID customerId, String customerCode, String customerName, String currency,
                             BigDecimal receivable, BigDecimal notYetDue, BigDecimal overdue1To30,
                             BigDecimal overdue31To60, BigDecimal overdue61To90, BigDecimal overdueOver90,
                             BigDecimal committedNotReached, BigDecimal creditLimit, Integer creditTermDays,
                             LocalDate oldestDueDate, int openItems) {

    /** Everything overdue, whatever its age. */
    public BigDecimal overdue() {
        return overdue1To30.add(overdue31To60).add(overdue61To90).add(overdueOver90);
    }

    /** The receivable and what signed contracts will make receivable on delivery: the credit check's exposure. */
    public BigDecimal exposure() {
        return receivable.add(committedNotReached);
    }

    /** {@code max(limit - exposure, 0)}; null without a limit. */
    public BigDecimal available() {
        return creditLimit == null ? null : creditLimit.subtract(exposure()).max(BigDecimal.ZERO);
    }

    public boolean hasOverdue() {
        return overdue().signum() > 0;
    }
}
