package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.inventory.api.ItemLogistics;
import com.stockflow.inventory.api.StorageClass;

import java.util.UUID;

/**
 * An inventory item addressed by its own id. The item is what stock and picking hang off, so it is read
 * and edited without going through a product or a variant: nothing here knows either exists. The version an edit
 * quotes back is the item's, and every method answers with the same {@link Detail} so a client never has to read
 * twice to see what it changed.
 *
 * <p>Logistics are the only group an edit replaces: the stock policy is fixed (lot-tracked, no serial numbers, first in
 * first out - kltn-docs 01 BR-17), so there is nothing to configure per item (team plan 5-5).</p>
 */
public interface InventoryItems {

    /** The item: identity and logistics. */
    record Detail(UUID id, String sku, long version, ItemLogistics logistics) {}

    /** One row of the item list: what a picker of items needs, without the dimensions of a detail. */
    record Summary(UUID id, String sku, String unitOfMeasure, String barcode, StorageClass storageClass) {}

    /**
     * @param sku exact match; {@code q} is a case-insensitive substring of the SKU or the barcode, with no wildcard
     *            meaning for {@code %} and {@code _}; null filters are not applied
     */
    record ItemQuery(String sku, String q, StorageClass storageClass, int page, int size) {}

    /** Items in SKU order, one page at a time. */
    PageResponse<Summary> search(ItemQuery query);

    /** @throws com.stockflow.common.error.BusinessException INVENTORY_ITEM_NOT_FOUND when no item has this id */
    Detail get(UUID id);

    /** Replaces the item's logistics when {@code expectedVersion} is still current. */
    Detail describe(UUID id, long expectedVersion, ItemLogistics logistics);
}
