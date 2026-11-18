package com.stockflow.payment.api;

import java.math.BigDecimal;

/**
 * Where an order's refund stands (kltn-docs 15 §5.3, team plan 4D-3): what its settlements made refundable -
 * cancellation, closure of the rest, returns - and what the accountant's live refund records paid out. Computed, never
 * stored.
 */
public record RefundProgress(BigDecimal refundable, BigDecimal refunded) {

    public static final RefundProgress NONE = new RefundProgress(BigDecimal.ZERO, BigDecimal.ZERO);

    /** Everything made refundable is refunded - nothing at all included (17 §4.10 step 40: "hoàn đủ"). */
    public boolean settled() {
        return refunded.compareTo(refundable) >= 0;
    }

    /**
     * What the seller still owes the customer: refundable less refunded, never below zero. Positive exactly when
     * {@link #settled} is false. The order is not closed while this is positive (TT-4): CLOSED is final, and a debt
     * behind it would no longer show on the order.
     */
    public BigDecimal outstanding() {
        return refundable.subtract(refunded).max(BigDecimal.ZERO);
    }
}
