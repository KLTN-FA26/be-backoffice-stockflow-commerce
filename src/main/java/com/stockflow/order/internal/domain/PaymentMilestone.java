package com.stockflow.order.internal.domain;

/**
 * When an installment of a contract's payment plan falls due (kltn-docs 14 §4.3 step 12, BR-13; 15 §4.1). The same three
 * milestones as a customer's default payment schedule, which a new contract's plan starts from.
 */
public enum PaymentMilestone {

    /** On signing (+ N days); unpaid, the order waits in PENDING_PAYMENT. Never tied to a delivery batch. */
    ON_SIGNING,
    /** Before the goods are handed to the carrier; unpaid, it blocks the handover - of its batch, when it names one. */
    BEFORE_DELIVERY,
    /** After delivery (+ N days): a receivable. Needs credit accounting approved (15 BR-12). */
    AFTER_DELIVERY
}
