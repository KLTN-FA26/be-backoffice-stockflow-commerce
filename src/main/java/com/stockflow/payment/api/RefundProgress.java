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
}
