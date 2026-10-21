package com.stockflow.order.api;

import java.util.UUID;

/**
 * Where an order stands, read in bulk (G4-5: the packing queue shows only orders packing may work on).
 *
 * @param holdReason set while the order is ON_HOLD
 */
public record OrderHoldState(UUID orderId, OrderStatus status, HoldReason holdReason) {
}
