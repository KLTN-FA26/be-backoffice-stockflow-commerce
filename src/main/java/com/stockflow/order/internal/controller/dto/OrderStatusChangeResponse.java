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
 * of the order, {@code from} and {@code to} null, {@code reason} the note. Staff only: a customer never sees the
 * second kind.</p>
 */
public record OrderStatusChangeResponse(
        @Schema(example = "STATUS_CHANGE", description = "STATUS_CHANGE or PARTIAL_SHIPMENT_DECISION")
        String type,
        OrderStatus from,
        OrderStatus to,
        String reason,
        Instant occurredAt,
        java.util.UUID actorId,
        String actorName,
        @Schema(description = "PARTIAL_SHIPMENT_DECISION only: whether partial shipments are allowed from then on")
        Boolean partialShipmentAllowed) {

    public static final String STATUS_CHANGE = "STATUS_CHANGE";
    public static final String PARTIAL_SHIPMENT_DECISION = "PARTIAL_SHIPMENT_DECISION";

    /** A status change. */
    public OrderStatusChangeResponse(OrderStatus from, OrderStatus to, String reason, Instant occurredAt,
                                     java.util.UUID actorId, String actorName) {
        this(STATUS_CHANGE, from, to, reason, occurredAt, actorId, actorName, null);
    }
}
