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
 * of the order, {@code from} and {@code to} null, {@code reason} the note. Staff only: a customer never sees the second
 * kind. {@code REMAINDER_CLOSURE} (WF4-CLOSE-HIST-SCHEMA) - Sales closed the rest of the order, whether or not that moved
 * its status ({@code from} and {@code to} null): {@code closureReasonCode} is the {@code CancellationReasonCode} and
 * {@code reason} the note, staff only like the partial-shipment decisions. The delivery-mode changes this history also listed went with the choice itself: every order ships as a full
 * truckload (kltn-docs 09 BR-08); V20261108000400 archived them.</p>
 */
public record OrderStatusChangeResponse(
        @Schema(example = "STATUS_CHANGE", description = "STATUS_CHANGE, PARTIAL_SHIPMENT_DECISION or REMAINDER_CLOSURE")
        String type,
        OrderStatus from,
        OrderStatus to,
        String reason,
        Instant occurredAt,
        java.util.UUID actorId,
        String actorName,
        @Schema(description = "PARTIAL_SHIPMENT_DECISION only: whether partial shipments are allowed from then on")
        Boolean partialShipmentAllowed,
        @Schema(description = "REMAINDER_CLOSURE only: the CancellationReasonCode the rest was closed for")
        String closureReasonCode) {

    public static final String STATUS_CHANGE = "STATUS_CHANGE";
    public static final String PARTIAL_SHIPMENT_DECISION = "PARTIAL_SHIPMENT_DECISION";
    public static final String REMAINDER_CLOSURE = "REMAINDER_CLOSURE";

    /** A status change. */
    public OrderStatusChangeResponse(OrderStatus from, OrderStatus to, String reason, Instant occurredAt,
                                     java.util.UUID actorId, String actorName) {
        this(STATUS_CHANGE, from, to, reason, occurredAt, actorId, actorName, null, null);
    }

    /** A partial-shipment decision. */
    public static OrderStatusChangeResponse partialShipmentDecision(boolean allowed, String note, Instant decidedAt,
                                                                    java.util.UUID actorId, String actorName) {
        return new OrderStatusChangeResponse(PARTIAL_SHIPMENT_DECISION, null, null, note, decidedAt, actorId, actorName,
                allowed, null);
    }

    /** A closure of the rest of the order. */
    public static OrderStatusChangeResponse remainderClosure(String reasonCode, String note, Instant closedAt,
                                                             java.util.UUID actorId, String actorName) {
        return new OrderStatusChangeResponse(REMAINDER_CLOSURE, null, null, note, closedAt, actorId, actorName, null,
                reasonCode);
    }
}
