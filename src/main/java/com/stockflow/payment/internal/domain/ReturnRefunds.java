package com.stockflow.payment.internal.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The refunds of a resolved return (SCRUM-260, refund source RETURN): asked of the money the order actually received,
 * as {@link CancelledOrderSettlement} asks a cancellation's - so a return never refunds more than was paid.
 */
public interface ReturnRefunds {

    /** A refund asked for a return: its id and what it asks. */
    record Asked(UUID refundId, BigDecimal amount) {
    }

    /**
     * One PENDING refund per payment or allocation of the order, newest first, until {@code amount} is covered; never
     * more of one than is left of it after the refunds it already carries (15 BR-05). The credit entries of returns
     * (channel RETURN_CREDIT) are not money and are never refunded from here.
     *
     * @return the refunds asked; their sum is less than {@code amount} when the order received less
     */
    List<Asked> requestReturnRefunds(UUID orderId, UUID returnRequestId, BigDecimal amount, String currency,
                                     String reason);

    /** Whether a refund was already asked for the return. */
    boolean hasRefunds(UUID returnRequestId);
}
