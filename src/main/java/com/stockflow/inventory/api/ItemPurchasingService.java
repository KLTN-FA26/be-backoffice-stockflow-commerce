package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

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

    /**
     * The default supplier of each of these items that has one; an item without one is absent. The supplier
     * catalog shows this supplier's row as the item's preferred one: the item's default supplier is the one
     * source of that fact (docs 01 §3 "Nhà cung cấp mặc định").
     */
    Map<UUID, UUID> defaultSuppliers(Collection<UUID> inventoryItemIds);

    /**
     * Makes {@code supplierId} the item's default supplier, as preferring a supplier in the catalog does. The
     * supplier is the caller's to check. Moves the item version when it changes anything, under the same
     * per-SKU lock as {@link #describe}. Joins the caller's transaction.
     *
     * @throws com.stockflow.common.error.BusinessException {@code INVENTORY_ITEM_NOT_FOUND}
     */
    void assignDefaultSupplier(UUID inventoryItemId, UUID supplierId);

    /**
     * Clears the item's default supplier if it is {@code supplierId}, as un-preferring that supplier's catalog
     * row does; a default that names another supplier is left alone.
     *
     * @throws com.stockflow.common.error.BusinessException {@code INVENTORY_ITEM_NOT_FOUND}
     */
    void clearDefaultSupplier(UUID inventoryItemId, UUID supplierId);
}
