package com.stockflow.contracts;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by the <b>fulfillment</b> module each time a shipment moves through kltn-docs 09 §5: handed to the carrier
 * on a signed manifest (G4-2a), delivered, not delivered, back at the warehouse, or cancelled before the handover.
 * Replaces {@code ShipmentDispatched}, which was never published: no carrier is called, so there is no tracking number
 * (09 §1).
 *
 * <p>The order does <b>not</b> follow its shipments through this event: fulfillment tells it in the same transaction
 * ({@code OrderService.recordHandover} / {@code recordShipment*}, architecture decision A1), so the money fixed at the
 * handover (09 BR-04) cannot drift from the order. This event is for the modules that only react: the customer's
 * message on handover (09 §4.1 step 3: "báo khách đơn đã giao cho đơn vị vận chuyển, không kèm tracking"), customer
 * care on a failed delivery, the returns receipt later (SCRUM-295).</p>
 *
 * @param eventId         unique per change: a listener that keeps what it handled uses it to skip a redelivery
 * @param previousStatus  null for a shipment just made
 * @param status          a {@code ShipmentStatus} name: READY_TO_DISPATCH, HANDED_OVER, DELIVERED, DELIVERY_FAILED,
 *                        RETURNED or CANCELLED
 * @param deliveryMode    PARCEL or FULL_TRUCKLOAD
 * @param failureReasonCode set with DELIVERY_FAILED: CUSTOMER_ABSENT, REFUSED, WRONG_ADDRESS, LOST, DAMAGED, OTHER
 *                        (the note staff wrote stays in fulfillment)
 * @param carrierName     null until a carrier is chosen
 * @param loadCode        the manifest it left on; null before the handover and for shipments handed over before loads
 * @param customerId      whom the customer's message is for: the listener looks the contact up when it delivers
 *                        (no personal data is kept in the event publication table)
 * @param recordedBy      who took the step (09 BR-02); null when the system did
 *
 * Consumers receive it in-process through {@code @ApplicationModuleListener}; delivery is made durable by Spring
 * Modulith's event publication registry. See {@code package-info.java} for the rules on changing this contract.
 */
public record ShipmentStatusChanged(
        UUID eventId,
        UUID shipmentId,
        String shipmentCode,
        UUID orderId,
        String orderNumber,
        UUID customerId,
        UUID warehouseId,
        String deliveryMode,
        String previousStatus,
        String status,
        String failureReasonCode,
        int packageCount,
        String carrierName,
        String loadCode,
        UUID recordedBy,
        Instant occurredAt
) {
}
