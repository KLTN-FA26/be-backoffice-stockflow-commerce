package com.stockflow.order.api;

import java.time.Instant;
import java.util.UUID;

/**
 * One transition of an order, oldest first in a history. {@code from} is null for the status the
 * order was placed in. {@code reason} is set for a cancellation, a hold and its resolution, a failed
 * delivery and a closed remainder: the code, and staff's note after a colon. {@code actorId} is who made
 * the change (G3-2), null when the system did or for rows written before it was recorded.
 */
public record OrderStatusChange(OrderStatus from, OrderStatus to, String reason, Instant occurredAt, UUID actorId) {

    public OrderStatusChange(OrderStatus from, OrderStatus to, String reason, Instant occurredAt) {
        this(from, to, reason, occurredAt, null);
    }
}
