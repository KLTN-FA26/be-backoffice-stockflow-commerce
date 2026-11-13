package com.stockflow.inventory.api;

import java.util.List;
import java.util.UUID;

/**
 * What {@link InventoryService#holdAtWarehouse} held (4B-7): whole cartons, possibly fewer than asked for - none when
 * the warehouse has none available. {@code reservationIds} is empty exactly when nothing was held.
 */
public record HoldStockResult(UUID orderId, String sku, String warehouseCode, int cartonsHeld,
                              List<UUID> reservationIds) {

    public HoldStockResult {
        reservationIds = List.copyOf(reservationIds);
        if (cartonsHeld < 0 || (cartonsHeld == 0) != reservationIds.isEmpty()) {
            throw new IllegalArgumentException("A hold of cartons has a reservation, and no hold has none");
        }
    }
}
