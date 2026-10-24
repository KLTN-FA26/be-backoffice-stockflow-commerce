package com.stockflow.order.internal.service;

import com.stockflow.order.api.DeliveryMode;
import com.stockflow.order.api.DeliveryModeChange;
import com.stockflow.order.api.OrderSummary;

import java.util.List;
import java.util.UUID;

/**
 * LP-1 / LP-2 (lead's decisions 2026-10-22): the Order Coordinator chooses how an order travels - parcels through a
 * carrier (the default) or a full truckload - at release or since, until packing starts. A full truckload never has its
 * balance collected on delivery.
 */
public interface DeliveryModes {

    /**
     * Change the order's delivery mode; the same mode again changes nothing and records nothing.
     *
     * @throws com.stockflow.common.error.BusinessException {@code NOT_FOUND}; {@code DELIVERY_MODE_LOCKED} once
     *         packing started (or the order is past it, cancelled or closed); {@code DELIVERY_MODE_FTL_NO_COD} for a
     *         full truckload whose balance the carrier would collect on delivery
     */
    OrderSummary change(UUID orderId, DeliveryMode mode, String note, UUID by);

    /** Every change of the order's delivery mode, oldest first. */
    List<DeliveryModeChange> changes(UUID orderId);
}
