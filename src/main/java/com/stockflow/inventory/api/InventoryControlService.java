package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

/**
 * Read-only view of a SKU's inventory control data for other modules. Nothing here edits an inventory item: only the
 * inventory module does, through its own item API, so the version check, stock compatibility and alert
 * re-evaluation live in one place.
 */
public interface InventoryControlService {
    InventoryControlItem item(Sku sku);

    InventoryPolicy policy(Sku sku);

    /** The SKU's physical and handling data. {@code INVENTORY_ITEM_NOT_FOUND} when it has no item. */
    InventoryItemLogistics logistics(Sku sku);

    /**
     * How many days before its expiry date a lot counts as near expiry
     * ({@code stockflow.inventory.expiry-warning-days}, 30 by default; docs 03 BR-06). A lot warns when
     * {@code expiryDate - referenceDate <= expiryWarningDays()}; it only warns, nothing is blocked.
     */
    int expiryWarningDays();
}
