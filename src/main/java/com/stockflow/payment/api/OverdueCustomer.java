package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A customer with receivables past their due date that it pays itself: the OVERDUE receivables the receivables screen
 * lists for it ({@code GET /api/v1/receivables?status=OVERDUE&collector=CUSTOMER}), summed.
 *
 * @param overdueAmount what is still unpaid on them, in {@code currency}
 * @param oldestDueDate the earliest due date among them
 * @param receivables   how many there are
 * @param orderNumbers  the orders they are for, earliest due first, as the receivables screen shows each; an old
 *                      receivable opened before the number was recorded has none
 */
public record OverdueCustomer(UUID customerId, BigDecimal overdueAmount, String currency, LocalDate oldestDueDate,
                              int receivables, java.util.List<String> orderNumbers) {
}
