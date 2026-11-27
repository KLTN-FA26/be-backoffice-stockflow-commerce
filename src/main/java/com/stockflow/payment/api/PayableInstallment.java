package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An installment a receipt may still pay (kltn-docs 15 BR-05: neither paid in full nor cancelled), as a module that
 * points people at it reads it: whose it is, what it still owes and how late it is. What the assistant's panels on the
 * bank transfer and the collections list show (2026-11-27 work features 2.1, 2.2); read-only.
 *
 * <p>The figures are the installment's own rules, worked out by the payment module, never by the reader:
 * {@code outstanding} is {@code Installment.outstanding()} (amount less what was paid and what a closure took off), the
 * same figure the bank feed compares a transfer with before it records a receipt; {@code overdue} is
 * {@code payment.installment_is_overdue} on the business day the row was read (overdue is a flag, never a status:
 * V20261121000100).</p>
 *
 * @param milestone         {@code ON_SIGNING}, {@code BEFORE_DELIVERY}, {@code AFTER_DELIVERY} or a fee item's kind
 * @param feeItem           a cancellation fee (or an old failed-trip fee) rather than a row of the contract's plan
 * @param amount            what it was for, before anything was paid or taken off
 * @param outstanding       what it still owes: more than zero
 * @param dueDate           null while its milestone has not come (an installment before delivery never has one)
 * @param status            {@code NOT_DUE}, {@code DUE} or {@code PARTIALLY_PAID}
 * @param daysOverdue       days past {@code dueDate} it is still owing on that business day; 0 when not overdue
 * @param transferReference what the customer writes in a transfer for it ({@code HD-... DOT2}, {@code ... FEE1}): what
 *                          the bank feed reads to record it without anyone's help
 */
public record PayableInstallment(UUID id, UUID customerId, UUID contractId, String contractNumber, UUID orderId,
                                 String orderNumber, int seq, String name, String milestone, boolean feeItem,
                                 BigDecimal amount, BigDecimal outstanding, String currency, LocalDate dueDate,
                                 String status, boolean overdue, int daysOverdue, String transferReference) {
}
