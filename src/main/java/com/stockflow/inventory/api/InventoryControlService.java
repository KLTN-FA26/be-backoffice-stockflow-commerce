package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

public interface InventoryControlService {
    /** Joins the product transaction. Rejects unsafe changes against live stock before applying. */
    InventoryControlItem configure(Sku sku, long expectedVersion, InventoryPolicy policy);

    InventoryControlItem item(Sku sku);

    InventoryPolicy policy(Sku sku);

    StockThresholdEvaluation evaluate(Sku sku);

    /** The SKU's physical and handling data. {@code INVENTORY_ITEM_NOT_FOUND} when it has no item. */
    InventoryItemLogistics logistics(Sku sku);

    /**
     * Replace the SKU's physical and handling data. Joins the caller's transaction. The unit of
     * measure is refused while any stock of the SKU is held: every quantity already counted is in
     * the old unit.
     */
    InventoryItemLogistics describe(Sku sku, long expectedVersion, ItemLogistics logistics);
}
