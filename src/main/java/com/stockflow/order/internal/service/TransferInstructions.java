package com.stockflow.order.internal.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Where and how much a customer transfers for their own order (SCRUM-193; kltn-docs 15 §4.1 step 1 and 14 §4.3: the
 * system shows the account and the transfer content, which is the order number).
 *
 * <p>Not on {@code order :: api}: only this module's controller drives it. The accountant still confirms the money
 * against the bank statement (15 BR-04); this is what the customer is told to send, never a record that they did.</p>
 */
public interface TransferInstructions {

    /** When the amount is wanted. */
    enum DueBy {
        /** The order waits for it: the prepayment, or the deposit, which confirms the order (15 BR-02). */
        TO_CONFIRM,
        /** The goods leave only once it is in (G3-Q4): the balance of an order paid before delivery. */
        BEFORE_DELIVERY,
        /** Nothing to transfer now: credit terms, a balance paid on delivery or on credit, or all paid. */
        NONE
    }

    /**
     * @param reference  what the customer writes as the transfer content: the order number (15 §3 "nội dung chuyển
     *                   khoản = mã đơn"), so the accountant matches the line of the statement to the order
     * @param amountDue  what is still due now, never below zero: the deposit while it is unpaid, else the balance due
     *                   before delivery, else 0
     * @param dueByNote  {@code dueBy} in words, in the caller's language
     */
    record Instructions(UUID orderId, String orderNumber, String bankName, String accountNumber, String accountName,
                        String reference, BigDecimal amountDue, String currency, DueBy dueBy, String dueByNote) {
    }

    /**
     * @param customerId the customer profile id, resolved by the controller from the sign-in; never from a request
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND} when the order does not exist or is not
     *         this customer's (do not reveal which); {@code TRANSFER_INSTRUCTIONS_UNAVAILABLE} when the company
     *         account is not configured
     */
    Instructions of(UUID customerId, UUID orderId);
}
