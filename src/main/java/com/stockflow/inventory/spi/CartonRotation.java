package com.stockflow.inventory.spi;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Asks the module that owns the catalogue whether the cartons of each variant may be turned (owner decision Q9 of
 * 2026-11-26): every SKU of a variant, its customers' SKUs included, is packed in the variant's carton, so the flag is
 * the variant's ({@code product.variants.rotatable}) and no longer an inventory item's. Putaway, the bin-fit checks and
 * the packaging resolver read it; inventory keeps no copy, which is what let a carton fix miss the SKUs already issued.
 * Implemented by {@code product}; see the package documentation for why the interface lives here.
 */
public interface CartonRotation {

    /**
     * Runs in the caller's transaction. One question per read, never one per carton.
     *
     * @param variantIds catalogue variant ids, as {@code inventory_items.variant_id} and
     *                   {@code uom_conversions.variant_id} store them
     * @return whether the cartons of each known variant may be turned; an unknown variant is absent and its cartons
     *         are treated as rotatable, the documented default (docs 01, E13)
     */
    Map<UUID, Boolean> rotatableOf(Collection<UUID> variantIds);
}
