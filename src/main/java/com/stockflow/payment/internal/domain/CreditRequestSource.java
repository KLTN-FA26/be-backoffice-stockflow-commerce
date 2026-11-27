package com.stockflow.payment.internal.domain;

/**
 * Who filed a credit request: {@code PORTAL} - the customer, for themselves, waiting for Sales' opinion; {@code SALES} -
 * Sales for the customer, with their opinion from the start.
 */
public enum CreditRequestSource {
    PORTAL,
    SALES
}
