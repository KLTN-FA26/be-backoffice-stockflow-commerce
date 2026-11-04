package com.stockflow.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * What {@link InventoryService#allocate} did: for every SKU reservation of the order it allocated, the holds that
 * replace it — one per bin and lot, in the order a picker should take them (FEFO). Empty when the order had no
 * SKU reservation left: allocated already, or placed before SKU reservations existed (its holds already sit on
 * stock items).
 */
public record AllocateStockResult(UUID orderId, String warehouseCode, List<Allocation> allocations) {

    public AllocateStockResult {
        allocations = List.copyOf(allocations);
    }

    /**
     * @param reservationId the SKU reservation the order line stored, now {@code ALLOCATED}
     * @param holds         the allocations that replace it on the line; their ids are what fulfillment picks and
     *                      what a cancellation releases from now on
     */
    public record Allocation(UUID reservationId, String sku, List<StockReservation> holds) {

        public Allocation {
            holds = List.copyOf(holds);
        }

        public List<UUID> holdIds() {
            return holds.stream().map(StockReservation::reservationId).toList();
        }
    }
}
