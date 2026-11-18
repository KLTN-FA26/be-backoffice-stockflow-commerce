package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Goods that came back on a return are refunded (team plan 4D-3; kltn-docs 17 §4.10 step 40, 15 §4.5 step 15): what
 * the order module hands {@link PaymentService#settleReturn} in the transaction that resolves the return.
 *
 * @param returnRequestId names this return: payment settles each return once
 * @param returnedAmount  what the goods refunded were worth with their VAT, a positive whole amount
 * @param settledBy       the second person who approved the refund (four eyes)
 * @param deliveryBatchId the delivery batch the returned goods came on (WF-05 TH-1): its installments take the value
 *                        first, so a return of batch 1 does not lower what batch 2 still owes for its own goods. Null
 *                        for a delivery that carried no batch, or an order delivered without shipments
 */
public record SettleReturnCommand(UUID orderId, UUID returnRequestId, BigDecimal returnedAmount, UUID settledBy,
                                  Instant settledAt, UUID deliveryBatchId) {

    /** Against the order's installments as a whole. */
    public SettleReturnCommand(UUID orderId, UUID returnRequestId, BigDecimal returnedAmount, UUID settledBy,
                               Instant settledAt) {
        this(orderId, returnRequestId, returnedAmount, settledBy, settledAt, null);
    }

    public SettleReturnCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(returnRequestId, "returnRequestId");
        Objects.requireNonNull(returnedAmount, "returnedAmount");
        Objects.requireNonNull(settledAt, "settledAt");
        if (returnedAmount.signum() <= 0) {
            throw new IllegalArgumentException("A return refunds a positive amount");
        }
    }
}
