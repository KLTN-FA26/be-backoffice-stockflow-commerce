package com.stockflow.fulfillment.internal.service;

import com.stockflow.order.api.OrderSummary;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * PACK-PARTIAL (lead's decision 2026-10-21, #150) as fulfillment applies it wherever goods leave: an order whose Sales
 * did not allow partial shipments leaves whole. Shared by the load plans (LP-4, SCRUM-287 PR D) and the vehicles'
 * handover (DispatchService). How much "whole" is belongs to the order ({@code OrderService.unitsDueTogether}): since
 * 4B-8 a scheduled order leaves batch by batch, so it is the released delivery batch, not the whole order (17 BR-15).
 */
final class PartialShipments {

    private PartialShipments() {
    }

    /** Whether the order may ship in parts; an order not found or without its lifecycle is not checked. */
    static boolean mayShipInParts(OrderSummary order) {
        return order == null || order.lifecycle() == null || order.lifecycle().allowPartialShipment();
    }

    /**
     * The units the order still wants to ship: ordered, less closed, less already shipped - the whole remainder, blind
     * to delivery batches. Only the fallback for an order the order module no longer answers for; the rule reads
     * {@code OrderService.unitsDueTogether}.
     */
    static int outstandingUnits(OrderSummary order) {
        Map<UUID, Integer> progress = new HashMap<>();
        Map<UUID, Integer> closed = new HashMap<>();
        order.lifecycle().lines().forEach(line -> {
            progress.put(line.lineId(), line.shippedQuantity());
            closed.put(line.lineId(), line.cancelledQuantity());
        });
        return order.lines().stream().mapToInt(line -> line.quantity() - closed.getOrDefault(line.lineId(), 0)
                - progress.getOrDefault(line.lineId(), 0)).sum();
    }
}
