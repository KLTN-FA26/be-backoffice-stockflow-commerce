package com.stockflow.customer.api;

/**
 * A payment term a customer may be sold on (kltn-docs 15 §1, 18 §3). Same names as the order's
 * {@code PaymentTerm}; the order module maps one to the other by name.
 */
public enum CommercialTerm {
    PREPAID,
    DEPOSIT,
    CREDIT
}
