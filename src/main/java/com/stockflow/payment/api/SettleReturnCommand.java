package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * The value a resolved return credits (SCRUM-260): the accepted units it does not replace, at the order's prices - or,
 * when its free replacement order is withdrawn before it ships, the replaced units at the same prices.
 *
 * @param rmaNumber  the return's number, named in the entries' descriptions
 * @param orderId    the order whose money settles it: the returned order's own, or for a return on a free replacement
 *                   order the order the replaced goods were first paid on
 * @param refundRest true (resolution REFUND): refunded - refused since 4B-10 until 4D-3; false (CREDIT_NOTE): credited,
 *                   settled outside the system (kltn-docs 15 BR-06)
 * @param settledBy  who approved the money (the second pair of eyes)
 * @param reference  the RMA number for the resolution, the RMA number with {@code -W} for the withdrawal of its
 *                   replacement; named in the log
 */
public record SettleReturnCommand(
        UUID returnRequestId,
        String rmaNumber,
        UUID orderId,
        String orderNumber,
        UUID customerId,
        BigDecimal amount,
        String currency,
        boolean refundRest,
        UUID settledBy,
        String reference
) {

    /** A return's resolution: the credit entry is referenced by the RMA number. */
    public SettleReturnCommand(UUID returnRequestId, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                               BigDecimal amount, String currency, boolean refundRest, UUID settledBy) {
        this(returnRequestId, rmaNumber, orderId, orderNumber, customerId, amount, currency, refundRest, settledBy,
                rmaNumber);
    }

    public SettleReturnCommand {
        Objects.requireNonNull(returnRequestId, "returnRequestId");
        Objects.requireNonNull(rmaNumber, "rmaNumber");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(settledBy, "settledBy");
        Objects.requireNonNull(reference, "reference");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("A return settles a positive amount");
        }
    }
}
