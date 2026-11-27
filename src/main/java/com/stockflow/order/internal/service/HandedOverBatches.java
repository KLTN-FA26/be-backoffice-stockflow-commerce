package com.stockflow.order.internal.service;

import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderShipment;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The delivery batches of an order whose goods left with a carrier - a shipment of the batch HANDED_OVER or DELIVERED
 * (WF-05 TT-5) - as payment is told when it settles a closure or a return. The batches not among them are still to
 * come, or, for a closure, what it closed: a failed shipment brought back (DELIVERY_FAILED, RETURNED) leaves its
 * batch among those. Payment lowers their installments first on a closure (15 §4.6 step 19) and last on a return, after
 * every dong paid is handed back (15 §4.5).
 */
final class HandedOverBatches {

    private HandedOverBatches() {
    }

    static Set<UUID> of(Order order) {
        return order.shipments().stream()
                .filter(shipment -> shipment.status() == OrderShipmentStatus.HANDED_OVER
                        || shipment.status() == OrderShipmentStatus.DELIVERED)
                .map(OrderShipment::deliveryBatchId)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }
}
