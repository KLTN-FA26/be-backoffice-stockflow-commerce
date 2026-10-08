package com.stockflow.order.internal.controller.dto;

import com.stockflow.order.api.OrderStatus;

import java.time.Instant;

/** One step of an order's status history. {@code from} is null for the status it was placed in. */
public record OrderStatusChangeResponse(OrderStatus from, OrderStatus to, String reason, Instant occurredAt) {
}
