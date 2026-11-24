package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Where a contract order's money stands when the contract is wound up: what the paper liquidating it prints. Read
 * from the payment module's own books - the installments, the receipts against them, the settlements of a
 * cancellation, a closure of the rest or a return, and the refunds recorded - so the balance the two parties sign is
 * the one the accountant's screens show, not a second sum made from the order's lines.
 */
public interface ContractLedger {

    /** A payment received against the order: the bank statement's day and reference, and the amount. */
    record Payment(LocalDate receivedOn, String reference, BigDecimal amount) {
    }

    /**
     * @param owed        what the customer owes for the contract in the end: the money kept of what was collected, and
     *                    what the installments still standing have left to pay - after every reduction and refund
     * @param returned    how much of the goods' worth returns took off; shown beside {@code owed}, not taken off it
     *                    again
     * @param payments    the receipts in force, oldest first; a voided one is the accountant's correction and absent
     * @param refunded    what was paid back to the customer, by the refunds in force
     * @param outstanding what the customer still has to pay
     * @param toRefund    what the company still has to pay back
     */
    record Standing(BigDecimal owed, BigDecimal returned, List<Payment> payments, BigDecimal refunded,
                    BigDecimal outstanding, BigDecimal toRefund) {
    }

    /** The order's money; all zero and no payment for an order with no installments. */
    Standing of(UUID orderId);
}
