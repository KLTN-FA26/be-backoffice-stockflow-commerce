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
     * The SKU's carton spec (kltn-docs 01 BR-03): its catalogue variant's one active carton (owner decision Q1) with
     * that carton's declared size and full weight, and the variant's may-rotate flag (Q9). Empty when the SKU has no
     * item or its variant no carton. The measures may still be undeclared ({@link CartonSpec#measured()}): no shipping
     * label prints then (08 BR-07). What a carton <em>being received or sold</em> is packed in; a carton already in
     * stock is described by {@link #cartonSpecsOfLpns}.
     */
    java.util.Optional<CartonSpec> cartonSpec(Sku sku);

    /**
     * The carton spec of each of these LPN cartons: the carton row it was received in
     * ({@code stock_package.uom_conversion_id}, fixed at receipt), as that row is measured now, with its variant's
     * may-rotate flag; {@code sku} is the carton's own SKU. After the company retires a carton and declares another
     * (BR-10), a carton received in the old one keeps being measured and labelled as the old one, never as the new
     * (plan 4.17). An unknown LPN is absent.
     */
    java.util.Map<String, CartonSpec> cartonSpecsOfLpns(java.util.Collection<String> lpns);
}
