package com.stockflow.order.api;

import java.util.UUID;

/**
 * Where an order stands, read in bulk (G4-5: the packing queue shows only orders packing may work on).
 *
 * @param holdReason           set while the order is ON_HOLD
 * @param allowPartialShipment Sales allowed it to ship in parts (PACK-PARTIAL): its stock may be packed while it is
 *                             printed (PACK-EARLY)
 */
public record OrderHoldState(UUID orderId, OrderStatus status, HoldReason holdReason, boolean allowPartialShipment) {

    /** Without the partial-shipment decision (callers written before PACK-PARTIAL). */
    public OrderHoldState(UUID orderId, OrderStatus status, HoldReason holdReason) {
        this(orderId, status, holdReason, false);
    }
}
