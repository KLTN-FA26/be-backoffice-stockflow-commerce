package com.stockflow.order.api;

/**
 * How a B2B order is paid (kltn-docs 15-payment §4), copied onto the order when it is placed:
 * changing a customer's terms later does not change what an existing order was sold on.
 */
public enum PaymentTerm {
    /** Paid in full before anything is released. Every order placed through checkout today. */
    PREPAID,
    /** A deposit before production (BR-PRD-09), the rest before delivery. */
    DEPOSIT,
    /** Paid after delivery, within the customer's credit limit (SCRUM-427). */
    CREDIT
}
