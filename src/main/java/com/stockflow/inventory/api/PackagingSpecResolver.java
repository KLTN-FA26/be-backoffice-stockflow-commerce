package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.util.List;
import java.util.UUID;

/**
 * Works out the carton an order line is packed in, from the inventory item's master data (SCRUM-287, decisions
 * LP2-2 to LP2-5). For each SKU, the first level that has a full size (length, width, height) and weight wins:
 *
 * <ol>
 *   <li>the unit marked {@code default_shipping} on the item's units of measure;</li>
 *   <li>the largest active packing unit that has them (most base units per unit, then by code);</li>
 *   <li>the item's box times {@code packageCount}: a kit of N same-size boxes when that is above 1;</li>
 *   <li>the item's unit size and weight.</li>
 * </ol>
 *
 * <p>A level with missing data is skipped and reported as a warning. When even the item's unit is incomplete the
 * line is {@link PackagingSpec.Status#MISSING}; the caller decides what that means (the estimate service answers 422
 * {@code LOAD_ESTIMATE_PACKAGING_MISSING} with a link to the item). Nothing is defaulted.</p>
 *
 * <p>Cartons are {@code ceil(quantity / factor)}: the full ones, and a remainder as one more carton of the same
 * size whose weight is the units' weight plus the carton's own packaging (LP2-4). The result depends only on the
 * data read, never on time or randomness, so the same input gives the same answer. Reads join the caller's
 * transaction.</p>
 */
public interface PackagingSpecResolver {

    /**
     * @param quantity in base units, at least 1 ({@code VALIDATION_FAILED} otherwise)
     * @throws com.stockflow.common.error.BusinessException {@code INVENTORY_ITEM_NOT_FOUND} when the SKU has no item
     */
    PackagingSpec resolve(Sku sku, int quantity);

    /** As {@link #resolve(Sku, int)}, for an inventory item addressed by its own id. */
    PackagingSpec resolve(UUID inventoryItemId, int quantity);

    /**
     * Many lines at once, with the item data read in two queries. The answers are in the order of the requests; a SKU
     * that appears twice is resolved for each quantity. {@code INVENTORY_ITEM_NOT_FOUND} naming the unknown SKUs
     * when any has no item.
     */
    List<PackagingSpec> resolveAll(List<PackagingRequest> lines);
}
