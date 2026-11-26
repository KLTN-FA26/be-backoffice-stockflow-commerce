package com.stockflow.support;

import com.stockflow.common.domain.Sku;
import com.stockflow.inventory.api.HoldStockCommand;
import com.stockflow.inventory.api.HoldStockResult;
import com.stockflow.inventory.api.InventoryService;
import com.stockflow.inventory.api.StockAvailability;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A SKU hold for a test's order, the way Sales takes one since the checkout reservation went (review C4 D-1,
 * V20261117000300): {@link InventoryService#holdAtWarehouse} at the order's shipping warehouse, no deadline.
 *
 * <p>A hold counts cartons of one count and is served only by cartons of that count (ADR-0011, V20261231000500), so a
 * test says how many cartons of which count it holds: {@link #cartons}. {@link #units} is for the many tests that
 * think in units of a SKU whose cartons all have one count: it reads that count from the SKU's free cartons at the
 * warehouse and holds {@code units / count} of them. It never guesses - units that are not a whole number of the
 * cartons there, or cartons of several counts that would each do, fail the test with the reason.</p>
 *
 * <p>Call inside the caller's transaction, as the application does.</p>
 */
public final class Holds {

    private Holds() {
    }

    /** {@code cartons} cartons of {@code unitsPerCarton} of {@code sku} held at {@code warehouse}; fails if fewer could be. */
    public static UUID cartons(InventoryService inventory, UUID order, String sku, String warehouse, int cartons,
                               int unitsPerCarton) {
        inventory.prepareReservation(Set.of(new Sku(sku)));
        HoldStockResult held = inventory.holdAtWarehouse(new HoldStockCommand(UUID.randomUUID(), order, new Sku(sku),
                warehouse, cartons, unitsPerCarton));
        if (held.cartonsHeld() != cartons) {
            throw new AssertionError("%d carton(s) of %d of %s could not be held at %s for %s: %d were".formatted(cartons,
                    unitsPerCarton, sku, warehouse, order, held.cartonsHeld()));
        }
        return held.reservationIds().get(0);
    }

    /**
     * {@code units} of {@code sku} held at {@code warehouse} for {@code order}, as whole cartons of the count its free
     * cartons there have; fails the test if fewer could be.
     */
    public static UUID units(InventoryService inventory, UUID order, String sku, String warehouse, int units) {
        int perCarton = cartonCountFor(inventory, sku, warehouse, units);
        return cartons(inventory, order, sku, warehouse, units / perCarton, perCarton);
    }

    /**
     * The same, without insisting: fewer cartons are held, or none, when the warehouse has fewer than {@code units} to
     * promise in cartons of that count.
     */
    public static HoldStockResult attempt(InventoryService inventory, UUID order, String sku, String warehouse,
                                          int units) {
        int perCarton = cartonCountFor(inventory, sku, warehouse, units);
        inventory.prepareReservation(Set.of(new Sku(sku)));
        return inventory.holdAtWarehouse(new HoldStockCommand(UUID.randomUUID(), order, new Sku(sku), warehouse,
                units / perCarton, perCarton));
    }

    /**
     * The count of the cartons {@code units} is held in: {@code units} itself when a carton of exactly that many
     * stands free (one carton), else the one count among the free cartons there that divides it. With no free carton
     * that could serve it, one carton of {@code units} is asked for - and nothing will be held.
     */
    private static int cartonCountFor(InventoryService inventory, String sku, String warehouse, int units) {
        List<Integer> counts = inventory.availabilityOf(new Sku(sku)).stream()
                .filter(row -> row.locationCode().startsWith(warehouse.toUpperCase() + "-"))
                .filter(row -> row.reserved() == 0 && row.onHand() > 0)
                .map(StockAvailability::onHand).distinct().toList();
        if (counts.contains(units)) {
            return units;
        }
        List<Integer> dividing = counts.stream().filter(count -> units % count == 0).toList();
        if (dividing.size() > 1) {
            throw new AssertionError("%d of %s at %s could be cartons of %s: say which with Holds.cartons"
                    .formatted(units, sku, warehouse, dividing));
        }
        return dividing.isEmpty() ? units : dividing.get(0);
    }
}
