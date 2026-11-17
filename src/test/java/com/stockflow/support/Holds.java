package com.stockflow.support;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.api.HoldStockCommand;
import com.stockflow.inventory.api.HoldStockResult;
import com.stockflow.inventory.api.InventoryService;

import java.util.Set;
import java.util.UUID;

/**
 * A SKU hold for a test's order, the way Sales takes one since the checkout reservation went (review C4 D-1,
 * V20261117000300): {@link InventoryService#holdAtWarehouse} at the order's shipping warehouse, no deadline. Tests that
 * hold a number of units rather than of cartons ask for one "carton" of exactly that many units, which is what
 * holdAtWarehouse sees anyway: it only multiplies the two.
 *
 * <p>Call inside the caller's transaction, as the application does.</p>
 */
public final class Holds {

    private Holds() {
    }

    /** {@code units} of {@code sku} held at {@code warehouse} for {@code order}; fails the test if fewer could be. */
    public static UUID units(InventoryService inventory, UUID order, String sku, String warehouse, int units) {
        HoldStockResult held = attempt(inventory, order, sku, warehouse, units);
        if (held.cartonsHeld() != 1) {
            throw new AssertionError("%d of %s could not be held at %s for %s".formatted(units, sku, warehouse, order));
        }
        return held.reservationIds().get(0);
    }

    /** The same, without insisting: nothing is held when the warehouse has fewer than {@code units} to promise. */
    public static HoldStockResult attempt(InventoryService inventory, UUID order, String sku, String warehouse,
                                          int units) {
        inventory.prepareReservation(Set.of(new Sku(sku)));
        return inventory.holdAtWarehouse(new HoldStockCommand(UUID.randomUUID(), order, new Sku(sku), warehouse, 1,
                units));
    }
}
