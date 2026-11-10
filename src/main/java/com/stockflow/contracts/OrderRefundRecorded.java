package com.stockflow.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>payment</b> module when the accountant records a refund made for an order, or voids one (team plan
 * 4D-3; kltn-docs 15 §4.5 steps 16-17, 17 §4.10 step 40): where the order's refund stands now - what its settlements
 * made refundable and what the live records refunded.
 *
 * <p>The <b>order</b> module takes it as the moment a returned order may be refunded in full: once everything to refund
 * is refunded and none of its returns is still open, RETURNED moves on to REFUNDED or PARTIALLY_REFUNDED. Totals, not a
 * delta, so a consumer that sees two of them in any order, or one twice, decides on the state the payment module was
 * in; it reads the standing again under the order's lock anyway.</p>
 */
public record OrderRefundRecorded(
        UUID orderId,
        String orderNumber,
        BigDecimal refundable,
        BigDecimal refunded,
        String currency,
        UUID recordedBy,
        Instant recordedAt
) {
}
