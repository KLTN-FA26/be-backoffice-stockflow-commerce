package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

/**
 * Read-only view of a SKU's inventory control data for other modules. Nothing here edits an inventory item: only the
 * inventory module does, through its own item API, so the version check and stock compatibility live in one place.
 */
public interface InventoryControlService {
    InventoryControlItem item(Sku sku);

    /** The SKU's physical and handling data. {@code INVENTORY_ITEM_NOT_FOUND} when it has no item. */
    InventoryItemLogistics logistics(Sku sku);

    /**
     * The SKU's carton spec (kltn-docs 01 BR-03): its carton unit - its one active packing unit, or the default shipping
     * one among several, the unit V20261111000110 boxed its stock in - with that carton's declared size and full weight,
     * and the item's may-rotate flag. Empty when the SKU has no item or no carton unit. The unit's
     * measures may still be undeclared ({@link CartonSpec#measured()}): no shipping label prints then (08 BR-07).
     */
    java.util.Optional<CartonSpec> cartonSpec(Sku sku);
}
