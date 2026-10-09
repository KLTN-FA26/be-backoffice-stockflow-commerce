package com.stockflow.order.api;

import java.time.Instant;

/**
 * One transition of an order, oldest first in a history. {@code from} is null for the status the
 * order was placed in. {@code reason} is set for a cancellation.
 */
public record OrderStatusChange(OrderStatus from, OrderStatus to, String reason, Instant occurredAt) {
}
