package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * The value a resolved return credits (SCRUM-260): the accepted units it does not replace, at the order's prices.
 *
 * @param rmaNumber  the return's number: the reference of the credit entry, once per return
 * @param refundRest true (resolution REFUND): what the order's receivables do not take is refunded; false
 *                   (CREDIT_NOTE): it stays as the customer's credit
 * @param settledBy  who approved the money (the second pair of eyes): the entries are recorded in their name
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
        UUID settledBy
) {

    public SettleReturnCommand {
        Objects.requireNonNull(returnRequestId, "returnRequestId");
        Objects.requireNonNull(rmaNumber, "rmaNumber");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(settledBy, "settledBy");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("A return settles a positive amount");
        }
    }
}
