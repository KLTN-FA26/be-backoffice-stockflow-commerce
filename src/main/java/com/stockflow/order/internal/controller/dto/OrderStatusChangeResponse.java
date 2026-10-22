package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.api.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * One entry of an order's history. {@code from} is null for the status it was placed in.
 *
 * <p>{@code reason}: the code and the note of a cancellation, a hold and its resolution, a failed delivery or a closed
 * remainder. A customer reads it on a cancellation only - a hold's note is the shop's. {@code actorId} and
 * {@code actorName} (G3-2) are who made the change, for staff only; null for a customer, for the system, and for steps
 * recorded before G3-2.</p>
 *
 * <p>{@code type} (review of #150): {@code STATUS_CHANGE} - the status moved {@code from} → {@code to};
 * {@code PARTIAL_SHIPMENT_DECISION} - Sales allowed ({@code partialShipmentAllowed = true}) or stopped partial shipments
 * of the order, {@code from} and {@code to} null, {@code reason} the note; {@code DELIVERY_MODE_CHANGE} (LP-1) - the
 * Order Coordinator changed how the order travels from {@code previousDeliveryMode} to {@code deliveryMode}, at release or
 * since, {@code from} and {@code to} null, {@code reason} the note. Staff only: a customer never sees the last two
 * kinds.</p>
 */
public record OrderStatusChangeResponse(
        @Schema(example = "STATUS_CHANGE", description = "STATUS_CHANGE, PARTIAL_SHIPMENT_DECISION or "
                + "DELIVERY_MODE_CHANGE")
        String type,
        OrderStatus from,
        OrderStatus to,
        String reason,
        Instant occurredAt,
        java.util.UUID actorId,
        String actorName,
        @Schema(description = "PARTIAL_SHIPMENT_DECISION only: whether partial shipments are allowed from then on")
        Boolean partialShipmentAllowed,
        @Schema(example = "FULL_TRUCKLOAD", description = "DELIVERY_MODE_CHANGE only: the mode from then on, PARCEL or "
                + "FULL_TRUCKLOAD")
        String deliveryMode,
        @Schema(example = "PARCEL", description = "DELIVERY_MODE_CHANGE only: the mode before the change")
        String previousDeliveryMode) {

    public static final String STATUS_CHANGE = "STATUS_CHANGE";
    public static final String PARTIAL_SHIPMENT_DECISION = "PARTIAL_SHIPMENT_DECISION";
    public static final String DELIVERY_MODE_CHANGE = "DELIVERY_MODE_CHANGE";

    /** A status change. */
    public OrderStatusChangeResponse(OrderStatus from, OrderStatus to, String reason, Instant occurredAt,
                                     java.util.UUID actorId, String actorName) {
        this(STATUS_CHANGE, from, to, reason, occurredAt, actorId, actorName, null, null, null);
    }

    /** A partial-shipment decision. */
    public static OrderStatusChangeResponse partialShipmentDecision(boolean allowed, String note, Instant decidedAt,
                                                                    java.util.UUID actorId, String actorName) {
        return new OrderStatusChangeResponse(PARTIAL_SHIPMENT_DECISION, null, null, note, decidedAt, actorId, actorName,
                allowed, null, null);
    }

    /** A change of the delivery mode. */
    public static OrderStatusChangeResponse deliveryModeChange(String mode, String previous, String note,
                                                               Instant changedAt, java.util.UUID actorId,
                                                               String actorName) {
        return new OrderStatusChangeResponse(DELIVERY_MODE_CHANGE, null, null, note, changedAt, actorId, actorName,
                null, mode, previous);
    }
}
