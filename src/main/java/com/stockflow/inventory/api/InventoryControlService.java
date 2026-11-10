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
     * The SKU's carton spec (kltn-docs 01 BR-03): its carton unit - its one active packing unit, or the default shipping
     * one among several, the unit V20261111000110 boxed its stock in - with that carton's declared size and full weight,
     * and the item's may-rotate flag. Empty when the SKU has no item or no carton unit. The unit's
     * measures may still be undeclared ({@link CartonSpec#measured()}): no shipping label prints then (08 BR-07).
     */
    java.util.Optional<CartonSpec> cartonSpec(Sku sku);

    /**
     * How many days before its expiry date a lot counts as near expiry
     * ({@code stockflow.inventory.expiry-warning-days}, 30 by default; docs 03 BR-06). A lot warns when
     * {@code expiryDate - referenceDate <= expiryWarningDays()}; it only warns, nothing is blocked.
     */
    int expiryWarningDays();
}
