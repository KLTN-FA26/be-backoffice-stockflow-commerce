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
 * @param shippingFeeWaived the order's shipping fee line, with its VAT, when this closure leaves the order with nothing
 *                          delivered and nothing more to deliver (Q-19: the failed trip's shipping is not charged, the
 *                          plate fee is kept); it comes off the installments and is refunded as the goods are. Zero
 *                          otherwise, and for a caller that does not say (the shorter constructor)
 */
public record SettleShipmentReturnCommand(UUID orderId, UUID shipmentId, UUID deliveryBatchId, UUID sourceId,
                                          BigDecimal goodsValue, Cause cause, Instant settledAt, UUID settledBy,
                                          Set<UUID> handedOverBatches, BigDecimal shippingFeeWaived) {

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
        shippingFeeWaived = shippingFeeWaived == null ? BigDecimal.ZERO : shippingFeeWaived;
        if (shippingFeeWaived.signum() < 0) {
            throw new IllegalArgumentException("A shipping fee waived is zero or more");
        }
        handedOverBatches = handedOverBatches == null ? null : Set.copyOf(handedOverBatches);
    }

    /** No shipping fee waived: the command as the WF-05 shared base declared it. */
    public SettleShipmentReturnCommand(UUID orderId, UUID shipmentId, UUID deliveryBatchId, UUID sourceId,
                                       BigDecimal goodsValue, Cause cause, Instant settledAt, UUID settledBy,
                                       Set<UUID> handedOverBatches) {
        this(orderId, shipmentId, deliveryBatchId, sourceId, goodsValue, cause, settledAt, settledBy,
                handedOverBatches, BigDecimal.ZERO);
    }
}
