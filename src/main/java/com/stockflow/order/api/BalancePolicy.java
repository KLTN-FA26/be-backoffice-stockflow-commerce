package com.stockflow.order.api;

/**
 * How a deposit order's balance is collected (kltn-docs 15 §4.2 step 3; G2-3), copied from the customer's terms when
 * the order is placed - Sales may tighten it to BEFORE_DELIVERY, never loosen it, and may put it ON_CREDIT for a customer
 * allowed credit - and kept with the order.
 */
public enum BalancePolicy {
    /** By transfer before the goods are handed to the carrier: the handover waits for it (decision G3-Q4). */
    BEFORE_DELIVERY,
    /**
     * Collected by the carrier on delivery (09 §2): the deposit is in before the handover, the carrier collects the rest
     * - the amount fixed at the handover and printed on the label (15 BR-08, 09 BR-04) - and remits it.
     */
    ON_DELIVERY,
    /**
     * BALCREDIT (kltn-docs 15 §4.2 step 3 "chuyển thành công nợ nếu khách được phép"; lead's decision 2026-10-21): the
     * deposit is in before the handover, the rest becomes the customer's receivable on delivery, due after the
     * customer's days to pay, as a credit order's. Only for a customer allowed CREDIT; the remaining balance is checked
     * against their credit limit when the order is placed (over it: refused, never held).
     */
    ON_CREDIT
}
