package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Goods that came back on a return are refunded (team plan 4D-3; kltn-docs 17 §4.10 step 40, 15 §4.5 step 15): what
 * the order module hands {@link PaymentService#settleReturn} in the transaction that resolves the return.
 *
 * @param returnRequestId   names this return: payment settles each return once
 * @param returnedAmount    what the goods refunded were worth with their VAT, a positive whole amount
 * @param settledBy         the second person who approved the refund (four eyes)
 * @param deliveryBatchId   the delivery batch the returned goods came on (WF-05 TH-1): its installments take the value
 *                          first, so a return of batch 1 does not lower what batch 2 still owes for its own goods. Null
 *                          for a delivery that carried no batch, or an order delivered without shipments
 * @param handedOverBatches the order's delivery batches with goods handed over to a carrier (WF-05 TT-5): the
 *                          installments of the others - batches still to come - are lowered only once nothing paid is
 *                          left to refund. Null when not known: every installment is the customer's debt, as before
 */
public record SettleReturnCommand(UUID orderId, UUID returnRequestId, BigDecimal returnedAmount, UUID settledBy,
                                  Instant settledAt, UUID deliveryBatchId, Set<UUID> handedOverBatches) {

    /** Against the order's installments as a whole. */
    public SettleReturnCommand(UUID orderId, UUID returnRequestId, BigDecimal returnedAmount, UUID settledBy,
                               Instant settledAt) {
        this(orderId, returnRequestId, returnedAmount, settledBy, settledAt, null, null);
    }

    /** Against the batch the goods came on, the batches still to come not known. */
    public SettleReturnCommand(UUID orderId, UUID returnRequestId, BigDecimal returnedAmount, UUID settledBy,
                               Instant settledAt, UUID deliveryBatchId) {
        this(orderId, returnRequestId, returnedAmount, settledBy, settledAt, deliveryBatchId, null);
    }

    public SettleReturnCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(returnRequestId, "returnRequestId");
        Objects.requireNonNull(returnedAmount, "returnedAmount");
        Objects.requireNonNull(settledAt, "settledAt");
        if (returnedAmount.signum() <= 0) {
            throw new IllegalArgumentException("A return refunds a positive amount");
        }
        handedOverBatches = handedOverBatches == null ? null : Set.copyOf(handedOverBatches);
    }
}
