package com.stockflow.inventory.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * Inventory's half of the warehouse capacity board (kltn-docs 06 §4.6, BR-20; BE1): what stands in a warehouse, what of
 * it is held for orders not yet delivered, and how big a SKU's carton is. One source of size for all three - the carton
 * spec of {@code inventory.uom_conversions}, the one putaway fits cartons into bins by - so what is coming (a production
 * order's cartons) is measured as what is already there.
 *
 * <p>A warehouse is named by its prefix: every location code of it starts {@code <prefix>-} (06 BR-10), and stock keeps
 * the code, not the warehouse (ADR-0007's foreign keys are to the location). Reads in the caller's transaction, no lock:
 * the board is computed when read, never stored (BR-20).</p>
 */
public interface WarehouseOccupancy {

    /** "Đang chiếm": the cartons IN_STOCK at any location of the warehouse. */
    OccupiedVolume occupiedIn(String warehousePrefix);

    /**
     * "Sắp xuất", for reference only (06 §4.6 step 3): the volume of the cartons held for orders not yet delivered at the
     * warehouse - the SKU holds taken there (whole cartons of the SKU's carton spec) and the allocations made from them,
     * each its carton until it is picked for dispatch. A hold of a SKU with no measured carton adds nothing.
     */
    BigDecimal heldVolumeM3(String warehousePrefix);

    /**
     * The volume of one carton of each SKU, in m³ to six decimals: its carton spec's length x width x height
     * ({@link InventoryControlService#cartonSpec}). A SKU with no carton spec, or one missing a dimension, is absent.
     */
    Map<String, BigDecimal> cartonVolumesM3(Collection<String> skus);

    /**
     * The load of each location of the warehouse (#456, the location list): what stands there, what putaway trips hold
     * there, and whether a count is open there. Grouped reads for the whole warehouse, never one per location. A
     * location with nothing standing, held or counted is absent - an empty bin.
     */
    Map<String, LocationLoad> loadsIn(java.util.UUID warehouseId, String warehousePrefix);
}
