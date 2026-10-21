package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.api.OrderStatus;

import java.time.Instant;

/**
 * One step of an order's status history. {@code from} is null for the status it was placed in.
 *
 * <p>{@code reason}: the code and the note of a cancellation, a hold and its resolution, a failed delivery or a closed
 * remainder. A customer reads it on a cancellation only - a hold's note is the shop's. {@code actorId} and
 * {@code actorName} (G3-2) are who made the change, for staff only; null for a customer, for the system, and for steps
 * recorded before G3-2.</p>
 *
 * <p>PACK-PARTIAL: staff also read Sales' decisions on shipping the order in parts here, in time order - an entry with
 * {@code from} = {@code to} (the status it was decided at) and {@code reason} {@code PARTIAL_SHIPMENT_ALLOWED} or
 * {@code PARTIAL_SHIPMENT_STOPPED}, followed by ": note" when one was given. A customer never sees them.</p>
 */
public record OrderStatusChangeResponse(OrderStatus from, OrderStatus to, String reason, Instant occurredAt,
                                        java.util.UUID actorId, String actorName) {
}
