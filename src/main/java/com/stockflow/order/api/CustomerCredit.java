package com.stockflow.order.api;

import java.math.BigDecimal;

/**
 * How much of its credit limit a customer has used and what is left, as the checkout's credit check counts it
 * (BR-PAY-005): the figure behind {@code GET /api/v1/orders/credit-position/{customerId}}.
 *
 * @param creditAllowed   the customer may buy on credit at all; without it the other figures but {@code exposure} are
 *                        null
 * @param creditLimit     the limit its commercial terms set
 * @param exposure        what counts against the limit: the receivables it still owes, plus the credit orders (and
 *                        deposit balances put on credit) placed and not yet billed, less what was paid on them; orders
 *                        waiting for a credit decision are not counted
 * @param availableCredit {@code creditLimit} less {@code exposure}, never below zero: what the next credit order may
 *                        come to without going on hold for the credit approver
 * @param hasOverdue      a receivable of its own is past its due date: no new credit until it is paid (BR-PAY-008),
 *                        whatever {@code availableCredit} says
 */
public record CustomerCredit(boolean creditAllowed, BigDecimal creditLimit, BigDecimal exposure,
                             BigDecimal availableCredit, String currency, boolean hasOverdue) {
}
