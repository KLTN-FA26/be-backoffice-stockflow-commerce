package com.stockflow.order.api;

/**
 * How a deposit order's balance is collected (kltn-docs 15 §4.2 step 3; G2-3), copied from the customer's terms when
 * the order is placed - Sales may tighten it to BEFORE_DELIVERY, never loosen it - and kept with the order.
 */
public enum BalancePolicy {
    /** By transfer before the goods are handed to the carrier: the handover waits for it (decision G3-Q4). */
    BEFORE_DELIVERY,
    /**
     * Collected by the carrier on delivery (09 §2): the deposit is in before the handover, the carrier collects the rest
     * - the amount fixed at the handover and printed on the label (15 BR-08, 09 BR-04) - and remits it.
     */
    ON_DELIVERY
}
