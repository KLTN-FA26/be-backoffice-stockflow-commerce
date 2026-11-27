package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The rest of a contract order was just closed (4B-9, kltn-docs 15 §4.6 step 19, 17 §4.8 step 33): what the order
 * module hands {@link PaymentService#settleRemainderClosure} in the closing transaction.
 *
 * @param closureId         names this closure: payment settles each closure once
 * @param closedAmount      what the goods closed were worth with their VAT - never the mould / print or shipping fee
 *                          lines, which stay owed in full (open questions D30, D32); a shipping fee waived is payment's
 *                          to add ({@code shippingFee})
 * @param feeAmount         an amount agreed with the customer for goods made before the closure, at most
 *                          {@code closedAmount}, with its reason and detail; null for none
 * @param handedOverBatches the order's delivery batches with goods handed over to a carrier (WF-05 TT-5): the others
 *                          are what the closure closed, and their installments are lowered first (15 §4.6 step 19).
 *                          Null when not known: the latest installments first, as before
 * @param shippingFee       the order's shipping fee, as for a shipment brought back
 *                          ({@link SettleShipmentReturnCommand.ShippingFee}): WF-05 Q-19 edge, as the owner decided on
 *                          2026-11-23 - a closure that leaves an order whose shipments came back or were lost with
 *                          nothing delivered and nothing more to deliver ({@code endsWithNothingDelivered}) waives what
 *                          the shipments brought back left of the fee, so the order is a whole return whichever came
 *                          first, the return or the closure. Otherwise the closure waives nothing and the fee stays
 *                          owed (D30, D32). {@link SettleShipmentReturnCommand.ShippingFee#NONE} for an order without a
 *                          shipping fee line; never null
 * @param deliveringBatches the batches among those with nothing handed over that still have goods to hand over after
 *                          this closure - labelled cartons it keeps (WF4-CLOSE-REST); empty for none. Every other
 *                          batch with nothing handed over is closed whole, and what its installment before delivery
 *                          still owes above the goods closed - its share of the fee lines - is carried onto the
 *                          closure's fee item, due from the closure's day (audit r3 LM-3): that installment's milestone
 *                          and its gate never come. Null when not known: nothing is carried, as before
 */
public record SettleRemainderClosureCommand(UUID orderId, UUID closureId, BigDecimal closedAmount,
                                            BigDecimal feeAmount, String feeReason, String feeDetail, UUID closedBy,
                                            Instant closedAt, Set<UUID> handedOverBatches,
                                            SettleShipmentReturnCommand.ShippingFee shippingFee,
                                            Set<UUID> deliveringBatches) {

    /**
     * Without the batches still delivering: nothing carried onto the fee item.
     *
     * @deprecated audit r3 LM-3: pass {@code deliveringBatches}; this form leaves what an installment before delivery of
     *             a batch closed whole still owes on it, with no milestone and no due day
     */
    @Deprecated
    public SettleRemainderClosureCommand(UUID orderId, UUID closureId, BigDecimal closedAmount, BigDecimal feeAmount,
                                         String feeReason, String feeDetail, UUID closedBy, Instant closedAt,
                                         Set<UUID> handedOverBatches,
                                         SettleShipmentReturnCommand.ShippingFee shippingFee) {
        this(orderId, closureId, closedAmount, feeAmount, feeReason, feeDetail, closedBy, closedAt, handedOverBatches,
                shippingFee, null);
    }

    /**
     * Without knowing which batches left - the latest installments first - nor the shipping fee: nothing waived.
     *
     * @deprecated WF-05 Q-19 edge: pass the order's {@link SettleShipmentReturnCommand.ShippingFee}; this form never
     *             waives the shipping fee of an order that ends with nothing delivered
     */
    @Deprecated
    public SettleRemainderClosureCommand(UUID orderId, UUID closureId, BigDecimal closedAmount, BigDecimal feeAmount,
                                         String feeReason, String feeDetail, UUID closedBy, Instant closedAt) {
        this(orderId, closureId, closedAmount, feeAmount, feeReason, feeDetail, closedBy, closedAt, null,
                SettleShipmentReturnCommand.ShippingFee.NONE);
    }

    /**
     * Without the shipping fee: nothing waived. Kept so callers written before the Q-19 edge still compile.
     *
     * @deprecated WF-05 Q-19 edge: pass the order's {@link SettleShipmentReturnCommand.ShippingFee}; this form never
     *             waives the shipping fee of an order that ends with nothing delivered
     */
    @Deprecated
    public SettleRemainderClosureCommand(UUID orderId, UUID closureId, BigDecimal closedAmount, BigDecimal feeAmount,
                                         String feeReason, String feeDetail, UUID closedBy, Instant closedAt,
                                         Set<UUID> handedOverBatches) {
        this(orderId, closureId, closedAmount, feeAmount, feeReason, feeDetail, closedBy, closedAt, handedOverBatches,
                SettleShipmentReturnCommand.ShippingFee.NONE);
    }

    public SettleRemainderClosureCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(closureId, "closureId");
        Objects.requireNonNull(closedAmount, "closedAmount");
        Objects.requireNonNull(closedAt, "closedAt");
        Objects.requireNonNull(shippingFee, "shippingFee (ShippingFee.NONE for an order without one)");
        handedOverBatches = handedOverBatches == null ? null : Set.copyOf(handedOverBatches);
        deliveringBatches = deliveringBatches == null ? null : Set.copyOf(deliveringBatches);
    }
}
