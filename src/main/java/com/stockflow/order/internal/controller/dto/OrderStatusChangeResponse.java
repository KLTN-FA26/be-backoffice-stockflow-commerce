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
 * {@code reason} the note, staff only like the partial-shipment decisions. {@code ADDRESS_CHANGE} (WF04 G2, kltn-docs 17
 * BR-16) - Sales changed the delivery address before the first label ({@code from} and {@code to} null):
 * {@code previousAddress} and {@code newAddress}, staff only too. {@code ON_BEHALF} (kltn-docs 17 BR-32, "làm hộ") -
 * someone other than the customer's Sales owner or their cover did operational work on the order: {@code onBehalfAction}
 * says what ({@code OnBehalfAction}), the actor is who did it, {@code actingForId} / {@code actingForName} the owner (or
 * cover) it was done for and who was told, null when the customer had no active owner. Staff only. The delivery-mode changes this history also listed went with the choice itself: every order ships as a full
 * truckload (kltn-docs 09 BR-08); V20261108000400 archived them.</p>
 */
public record OrderStatusChangeResponse(
        @Schema(example = "STATUS_CHANGE",
                description = "STATUS_CHANGE, PARTIAL_SHIPMENT_DECISION, REMAINDER_CLOSURE, ADDRESS_CHANGE, "
                        + "REFUND_VOIDED or ON_BEHALF")
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
        String closureReasonCode,
        @Schema(description = "ADDRESS_CHANGE only: the delivery address before the change")
        OrderResponse.Address previousAddress,
        @Schema(description = "ADDRESS_CHANGE only: the delivery address after the change")
        OrderResponse.Address newAddress,
        @Schema(description = "ON_BEHALF only: what was done for the customer's owner, e.g. CHOOSE_SHIPPING_WAREHOUSE, "
                + "HOLD, RESOLVE_HOLD, APPROVE_CANCELLATION_REQUEST, CHANGE_SHIPPING_ADDRESS")
        String onBehalfAction,
        @Schema(description = "ON_BEHALF only: the owner (or cover) it was done for; null when the customer had none")
        java.util.UUID actingForId,
        @Schema(description = "ON_BEHALF only: their display name")
        String actingForName) {

    public static final String STATUS_CHANGE = "STATUS_CHANGE";
    public static final String PARTIAL_SHIPMENT_DECISION = "PARTIAL_SHIPMENT_DECISION";
    public static final String REMAINDER_CLOSURE = "REMAINDER_CLOSURE";
    public static final String ADDRESS_CHANGE = "ADDRESS_CHANGE";
    public static final String REFUND_VOIDED = "REFUND_VOIDED";
    public static final String ON_BEHALF = "ON_BEHALF";

    /** 17 BR-32: operational work done for the customer's owner by someone else ("làm hộ"). Staff only. */
    public static OrderStatusChangeResponse onBehalf(String action, Instant actedAt, java.util.UUID actorId,
                                                     String actorName, java.util.UUID actingForId,
                                                     String actingForName) {
        return new OrderStatusChangeResponse(ON_BEHALF, null, null, null, actedAt, actorId, actorName, null, null, null,
                null, action, actingForId, actingForName);
    }

    /**
     * WF-05 BE-52: a refund record of the order voided while a claim of it was open - no status moved ({@code from}
     * and {@code to} null); {@code reason} is the void's. Staff only.
     */
    public static OrderStatusChangeResponse refundVoided(String reason, Instant voidedAt, java.util.UUID actorId,
                                                         String actorName) {
        return new OrderStatusChangeResponse(REFUND_VOIDED, null, null, reason, voidedAt, actorId, actorName, null,
                null, null, null, null, null, null);
    }

    /** A status change. */
    public OrderStatusChangeResponse(OrderStatus from, OrderStatus to, String reason, Instant occurredAt,
                                     java.util.UUID actorId, String actorName) {
        this(STATUS_CHANGE, from, to, reason, occurredAt, actorId, actorName, null, null, null, null, null, null, null);
    }

    /** A partial-shipment decision. */
    public static OrderStatusChangeResponse partialShipmentDecision(boolean allowed, String note, Instant decidedAt,
                                                                    java.util.UUID actorId, String actorName) {
        return new OrderStatusChangeResponse(PARTIAL_SHIPMENT_DECISION, null, null, note, decidedAt, actorId, actorName,
                allowed, null, null, null, null, null, null);
    }

    /** A closure of the rest of the order. */
    public static OrderStatusChangeResponse remainderClosure(String reasonCode, String note, Instant closedAt,
                                                             java.util.UUID actorId, String actorName) {
        return new OrderStatusChangeResponse(REMAINDER_CLOSURE, null, null, note, closedAt, actorId, actorName, null,
                reasonCode, null, null, null, null, null);
    }

    /** A change of the delivery address. */
    public static OrderStatusChangeResponse addressChange(OrderResponse.Address previous, OrderResponse.Address next,
                                                          Instant changedAt, java.util.UUID actorId, String actorName) {
        return new OrderStatusChangeResponse(ADDRESS_CHANGE, null, null, null, changedAt, actorId, actorName, null, null,
                previous, next, null, null, null);
    }
}
