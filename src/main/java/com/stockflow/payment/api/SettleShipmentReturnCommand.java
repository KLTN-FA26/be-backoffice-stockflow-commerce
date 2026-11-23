package com.stockflow.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The units of a shipment that will not be delivered were closed (WF-05 decisions Q-2, Q-7, Q-12): what the order
 * module hands {@link PaymentService#settleShipmentReturn} in the transaction that closes them - the confirmation of
 * the return receipt of a shipment the carrier brought back, or Sales' closure of a shipment the carrier lost.
 *
 * @param shipmentId        the shipment whose units were closed
 * @param deliveryBatchId   the delivery batch the shipment carried: its installments take the value first (Q-2). Null
 *                          for an order without a delivery schedule
 * @param sourceId          names this closure: payment settles each once (the order's closure id, published as
 *                          {@code ShipmentReturnSettled.closureId})
 * @param goodsValue        what the closed units were worth with their VAT - never the plate or shipping fee lines;
 *                          zero or more
 * @param cause             why the units were closed
 * @param settledAt         the transaction clock, never the carrier's time (BE-32)
 * @param settledBy         who confirmed the receipt, or closed the lost shipment
 * @param handedOverBatches the order's delivery batches with goods handed over to a carrier: the installments of the
 *                          others - batches still to come - are lowered last, once nothing paid is left to refund.
 *                          Null when not known
 * @param shippingFee       the order's shipping fee and the goods it was charged for (WF-05 Q-19, as the owner decided
 *                          on 2026-11-23): payment waives the share of the fee this shipment's goods are of the order's
 *                          goods ({@link ShippingFee}). {@link ShippingFee#NONE} for an order without a shipping fee
 *                          line; never null
 */
public record SettleShipmentReturnCommand(UUID orderId, UUID shipmentId, UUID deliveryBatchId, UUID sourceId,
                                          BigDecimal goodsValue, Cause cause, Instant settledAt, UUID settledBy,
                                          Set<UUID> handedOverBatches, ShippingFee shippingFee) {

    /** Why the shipment's units were closed without being delivered. */
    public enum Cause {
        /** The carrier brought the goods back and the warehouse confirmed their return receipt (Q-7). */
        CARRIER_RETURN,
        /** The carrier lost the parcel and confirmed it; Sales closed the shipment (Q-12). */
        LOST
    }

    public SettleShipmentReturnCommand {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(shipmentId, "shipmentId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(goodsValue, "goodsValue");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(settledAt, "settledAt");
        if (goodsValue.signum() < 0) {
            throw new IllegalArgumentException("Closed goods are worth zero or more");
        }
        Objects.requireNonNull(shippingFee, "shippingFee (ShippingFee.NONE for an order without one)");
        handedOverBatches = handedOverBatches == null ? null : Set.copyOf(handedOverBatches);
    }

    /**
     * The order's shipping fee and what it was charged for, as the order was signed: payment works the share waived with
     * each shipment out of these (WF-05 Q-19; {@code OrderSettlement.shippingFeeShare}). Both are with VAT, in whole
     * units of the currency.
     *
     * <p>There is deliberately no shorter constructor of the command that leaves this out: one that defaulted to "no
     * fee" would let a caller that forgot the fee charge it for goods that never arrived, silently.</p>
     *
     * @param fee             the order's shipping fee line with its VAT; zero when the order has none
     * @param orderGoodsValue what all of the order's goods lines are worth with their VAT - never the plate or shipping
     *                        fee lines - as the order was signed, not as later closures left it: the shares of the
     *                        shipments brought back must add up against one fixed whole, so that every one of them
     *                        brought back waives the whole fee. Positive whenever {@code fee} is
     */
    public record ShippingFee(BigDecimal fee, BigDecimal orderGoodsValue) {

        /** An order without a shipping fee line: nothing to waive. */
        public static final ShippingFee NONE = new ShippingFee(BigDecimal.ZERO, BigDecimal.ZERO);

        public ShippingFee {
            Objects.requireNonNull(fee, "fee");
            Objects.requireNonNull(orderGoodsValue, "orderGoodsValue");
            if (fee.signum() < 0 || fee.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("A shipping fee is zero or more, in whole units, not " + fee);
            }
            if (orderGoodsValue.signum() < 0 || fee.signum() > 0 && orderGoodsValue.signum() == 0) {
                throw new IllegalArgumentException("A shipping fee is charged for goods worth more than zero, not "
                        + orderGoodsValue);
            }
        }
    }
}
