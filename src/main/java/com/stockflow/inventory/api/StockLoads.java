package com.stockflow.inventory.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * How much room cartons take, for the warehouse capacity board (kltn-docs 06 §4.6): what stands at given locations,
 * and what is held for orders not yet delivered. Reads only, in the caller's transaction, no locks. Kept apart from
 * {@link InventoryService}, which moves and holds stock: the board only measures it.
 *
 * <p>A carton takes its carton unit's length x width x height and weighs the unit's full weight, else the item's unit
 * weight times its standard count - the figures putaway counts (05 BR-03). A carton whose unit has no full size takes
 * 0 m³ and is counted in {@link Load#unmeasuredCartons()}, so a caller knows the volume is low.</p>
 */
public interface StockLoads {

    /**
     * The cartons with units in them standing at each of these locations, whatever their stock status (a quarantined
     * or held carton fills a bin like any other).
     *
     * @return a location holding nothing is absent
     */
    Map<String, Load> loadAt(Collection<String> locationCodes);

    /**
     * What is held for orders of a warehouse and not yet picked or delivered: its SKU holds (rounded up to whole
     * cartons of the SKU's standard count) and its allocations (one carton each, wherever it stands, a staging area
     * included).
     *
     * @param warehouseCode the warehouse's prefix, as reservations name it
     */
    Load outboundAt(String warehouseCode);

    /** Cartons, their volume in m³ and full weight in kg; {@code unmeasuredCartons} of them have no full size. */
    record Load(long cartons, BigDecimal volumeM3, BigDecimal weightKg, long unmeasuredCartons) {

        public static final Load NONE = new Load(0, BigDecimal.ZERO, BigDecimal.ZERO, 0);

        public Load plus(Load other) {
            return new Load(cartons + other.cartons, volumeM3.add(other.volumeM3), weightKg.add(other.weightKg),
                    unmeasuredCartons + other.unmeasuredCartons);
        }
    }
}
