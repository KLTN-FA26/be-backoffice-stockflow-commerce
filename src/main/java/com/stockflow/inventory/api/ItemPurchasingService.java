package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

/**
 * Reads and replaces the purchasing information of a SKU's inventory item (docs 01 §3). The item's
 * version is shared with its logistics and stock policy: any edit of the item moves it on.
 *
 * <p>The default supplier is stored as given: whether it exists and is active is for the caller to
 * check through the supplier's own module, which this one cannot depend on (procurement depends on
 * inventory).</p>
 */
public interface ItemPurchasingService {

    /** {@code INVENTORY_ITEM_NOT_FOUND} when the SKU has no item. */
    InventoryItemPurchasing purchasing(Sku sku);

    /**
     * Replace the purchasing information. Joins the caller's transaction.
     *
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} (with the field),
     *         {@code OPTIMISTIC_LOCK} when {@code expectedVersion} is stale
     */
    InventoryItemPurchasing describe(Sku sku, long expectedVersion, ItemPurchasing purchasing);
}
