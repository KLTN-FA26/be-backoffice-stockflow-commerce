package com.stockflow.payment.internal.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What payment does with the money of a cancelled order (SCRUM-460, kltn-docs 15 §4.4, 17 §4.5):
 * a payment still awaited is cancelled — there is nothing to refund — and the refundable amount is
 * asked back from the captured payments, newest first, as PENDING refunds for the accountant to
 * approve and pay out (SCRUM-220).
 */
public interface CancelledOrderSettlement {

    /** Payments still awaited for the order, now cancelled. */
    int cancelAwaitedPayments(UUID orderId);

    /**
     * One PENDING refund per captured payment, newest first, until {@code refundable} is covered.
     * Idempotent: a payment already asked back for this cancellation is skipped.
     *
     * @return what could not be placed on a captured payment of this order (0 when all of it was)
     */
    BigDecimal requestRefunds(UUID orderId, BigDecimal refundable, String currency, String reason);
}
