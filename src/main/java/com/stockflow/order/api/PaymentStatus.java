package com.stockflow.order.api;

/**
 * Where an order stands with the money (kltn-docs 15 §5.2). Derived from the payment term, the
 * amount received and the deposit, never stored: two fields that must agree are one too many.
 * Refunds ({@code Refunded} / {@code Partially Refunded} in the docs) come with the refund flow
 * (SCRUM-220).
 */
public enum PaymentStatus {
    /** Nothing received yet on a prepaid or deposit order. */
    UNPAID,
    /** Some money in — typically the deposit — and some still due. */
    PARTIALLY_PAID,
    /** A credit order: paid on its due date, after delivery (SCRUM-427). */
    ON_CREDIT,
    PAID;

    /** Derived: paid in full wins, then credit terms, then whether anything has arrived. */
    public static PaymentStatus of(PaymentTerm term, java.math.BigDecimal paidAmount, java.time.Instant paidInFullAt) {
        if (paidInFullAt != null) {
            return PAID;
        }
        if (term == PaymentTerm.CREDIT) {
            return ON_CREDIT;
        }
        return paidAmount != null && paidAmount.signum() > 0 ? PARTIALLY_PAID : UNPAID;
    }
}
