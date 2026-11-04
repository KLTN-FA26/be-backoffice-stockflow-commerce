package com.stockflow.order.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Sales allowed, or stopped, shipping an order in parts (PACK-PARTIAL, lead's decision 2026-10-21): one entry of the
 * order's history of that decision.
 *
 * @param orderStatus the order's status when it was decided
 * @param decidedBy   who decided it
 */
public record PartialShipmentDecision(boolean allowed, OrderStatus orderStatus, String note, UUID decidedBy,
                                      Instant decidedAt) {
}
