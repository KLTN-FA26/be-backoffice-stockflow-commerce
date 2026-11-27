package com.stockflow.warehouse.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The map's half of the warehouse capacity board (kltn-docs 06 §4.6; BE1): how much volume a warehouse's bins offer.
 * The board itself is worked out by the order module, the one that needs it (choosing a shipping warehouse, drawing up
 * and approving production orders, 17 BR-25) and the only one allowed to read the three sources it needs; this module
 * calls no other, so it answers only for its map.
 *
 * <p>A read in the caller's transaction when there is one; no lock. Computed when read, never stored (06 BR-20): a bin
 * resized or blocked a moment ago counts as it is now.</p>
 */
public interface WarehouseCapacities {

    /** @return empty when no warehouse has this id */
    Optional<WarehouseCapacity> capacityOf(UUID warehouseId);

    /**
     * Every bin of the warehouse on the layout (its shelf not taken off it), by location code, with its volume, weight
     * limit and whether it is usable now (#456, the location list). One read; an unknown warehouse has none.
     */
    java.util.List<BinCapacity> binsOf(UUID warehouseId);
}
