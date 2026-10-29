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
    /**
     * A free replacement order withdrawn through its return (SCRUM-260): the replaced units are credited instead. The
     * only way such an order is cancelled - it ships goods a return already promised.
     */
    REPLACEMENT_WITHDRAWN,
    /** Needs a note: "other" with no explanation is not a reason. */
    OTHER;

    /**
     * Whether a customer may give it when cancelling their own order or asking to: their own reasons only. Payment,
     * stock, fraud, credit and production reasons are the shop's or the system's to record (G3-2).
     */
    public boolean customerMayGive() {
        return this == CUSTOMER_REQUEST || this == DUPLICATE_ORDER || this == OTHER;
    }
}
