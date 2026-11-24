package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>order</b> module when the units of a shipment that will not be delivered were closed and their
 * money settled (WF-05 decisions Q-2, Q-3, Q-7, Q-12): a shipment the carrier brought back, once the warehouse
 * confirmed its return receipt ({@link Cause#CARRIER_RETURN}), or a shipment the carrier lost, once Sales closed it
 * with the carrier's confirmation ({@link Cause#LOST}). Published after the transaction that closed the units and
 * called payment's {@code settleShipmentReturn} commits, once per closed shipment.
 *
 * <p>What it is the fact for: <b>payment</b> re-reads when the order (and the shipment's batch) counts as delivered in
 * full ({@code InstallmentOrders.deliveredInFullAt} / {@code batchesDeliveredAt}) and reaches or re-dates the
 * installments paid after delivery - due from the later of the last delivery and {@code goodsBackAt}, plus the
 * contract's days (Q-3, Q-19). <b>invoicing</b> draws up the rest of the order's fees - the plate fee no delivery's
 * invoice carries - once {@code nothingMoreComing} (Q-19, BE-62). Not {@link OrderRemainderClosed}: that one also
 * starts fulfillment's clean-up of a closed rest, which a returned shipment has no part in.</p>
 *
 * <p>Ids, causes and instants only. Consumers receive it in-process through {@code @ApplicationModuleListener} and are
 * idempotent on {@code closureId}; see {@code package-info.java} for the rules on changing this contract.</p>
 *
 * @param deliveryBatchId   the batch of the order's delivery schedule the shipment carried; null without a schedule
 * @param closureId         names this closure - one per closed shipment; the {@code sourceId} payment settled it under
 * @param settledAt         when the money was settled: the transaction clock, never the carrier's time (BE-32)
 * @param goodsBackAt       when the goods were back: the return receipt's confirmation (Q-7), or the moment Sales closed
 *                          the lost shipment (Q-12). The day installments after delivery count from, when it is later
 *                          than the last delivery
 * @param nothingDelivered  no shipment of the order is DELIVERED: the contract ends LIQUIDATED once money is settled (Q-6)
 * @param nothingMoreComing no shipment of the order is HANDED_OVER or DELIVERY_FAILED with units owed, and nothing is
 *                          left to ship: the order's deliveries are over
 */
public record ShipmentReturnSettled(UUID orderId, String orderNumber, UUID customerId, UUID shipmentId,
                                    UUID deliveryBatchId, UUID closureId, Cause cause, Instant settledAt,
                                    Instant goodsBackAt, boolean nothingDelivered, boolean nothingMoreComing) {

    /** Why the shipment's units were closed without being delivered. */
    public enum Cause {
        /** The carrier brought the goods back and the warehouse confirmed the return receipt (Q-7). */
        CARRIER_RETURN,
        /** The carrier lost the parcel and confirmed it; Sales closed the shipment's units (Q-12). */
        LOST
    }
}
