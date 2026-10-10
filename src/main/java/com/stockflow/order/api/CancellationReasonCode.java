package com.stockflow.order.api;

/**
 * Why an order was cancelled (SCRUM-460). The sources are those of kltn-docs 17 §4.5 — the
 * customer asked, payment did not arrive or failed, the goods cannot be supplied, suspected fraud —
 * plus a credit request refused (15 §4.3) and a problem in production (19).
 *
 * <p>Mapped by name onto {@code ordering.customer_order.cancellation_reason_code}, whose CHECK lists
 * the same values.</p>
 */
public enum CancellationReasonCode {
    CUSTOMER_REQUEST,
    /** The stock hold ran out before the money arrived (kltn-docs 14 BR-07). */
    PAYMENT_NOT_RECEIVED,
    PAYMENT_FAILED,
    OUT_OF_STOCK,
    SUSPECTED_FRAUD,
    /** An order over the customer's credit limit that was not approved (kltn-docs 15 §4.3). */
    CREDIT_REJECTED,
    DUPLICATE_ORDER,
    PRODUCTION_ISSUE,
    /** Needs a note: "other" with no explanation is not a reason. */
    OTHER
}
