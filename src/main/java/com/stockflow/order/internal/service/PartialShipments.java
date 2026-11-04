package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PartialShipmentDecision;

import java.util.List;
import java.util.UUID;

/**
 * PACK-PARTIAL (lead's decision 2026-10-21): Sales decides on the order whether it may ship in parts - a packing
 * completion that leaves goods behind, a handover of part of it, the stock of a mixed order shipped while it is
 * printed. The warehouse no longer decides it (it still says why when it packs part of an order).
 */
public interface PartialShipments {

    /**
     * Allow or stop partial shipments of the order; the same decision again changes nothing and records nothing.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code ORDER_PARTIAL_SHIPMENT_LOCKED} once
     *         everything left (SHIPPED and later) or the order is cancelled or closed
     */
    OrderSummary decide(UUID orderId, boolean allowed, String note, UUID by);

    /** Every decision on the order, oldest first. */
    List<PartialShipmentDecision> decisions(UUID orderId);
}
