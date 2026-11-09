package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

/**
 * Read-only view of a SKU's inventory control data for other modules. Nothing here edits an inventory item: only the
 * inventory module does, through its own item API, so the version check and stock compatibility live in one place.
 */
public interface InventoryControlService {
    InventoryControlItem item(Sku sku);

    InventoryPolicy policy(Sku sku);

    /** The SKU's physical and handling data. {@code INVENTORY_ITEM_NOT_FOUND} when it has no item. */
    InventoryItemLogistics logistics(Sku sku);
}
