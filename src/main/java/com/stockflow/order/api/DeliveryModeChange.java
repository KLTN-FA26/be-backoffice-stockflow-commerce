package com.stockflow.order.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The Order Coordinator changed how an order travels (LP-1, lead's decision 2026-10-22): one entry of the order's
 * history of that choice - at release, or since, until packing started.
 *
 * @param previousMode the mode before the change
 * @param orderStatus  the order's status once it was changed (READY_TO_FULFILL or IN_PRODUCTION for a choice at
 *                     release)
 * @param changedBy    who changed it
 */
public record DeliveryModeChange(DeliveryMode deliveryMode, DeliveryMode previousMode, OrderStatus orderStatus,
                                 String note, UUID changedBy, Instant changedAt) {
}
