package com.stockflow.customer.api;

/**
 * When an instalment of a payment schedule falls due (kltn-docs 14 BR-13, 15 §3): at the contract's signing, before a
 * delivery leaves, or after the goods arrived - the last only for a customer with credit approved by accounting
 * (18 BR-03). Each is followed by N days.
 */
public enum PaymentMilestone {
    ON_SIGNING,
    BEFORE_DELIVERY,
    AFTER_DELIVERY
}
