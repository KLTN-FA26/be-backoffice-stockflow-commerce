package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.util.List;
import java.util.UUID;

/**
 * Works out the carton an order line is packed in, from the inventory item's carton unit (SCRUM-287, decisions
 * LP2-2 to LP2-4). For each SKU, the first level that has a full size (length, width, height) and weight wins:
 *
 * <ol>
 *   <li>the unit marked {@code default_shipping} on the item's units of measure;</li>
 *   <li>the largest active packing unit that has them (most base units per unit, then by code).</li>
 * </ol>
 *
 * <p>A SKU has one active carton (01 BR-03), so in practice both levels name it. The item itself has no size or weight
 * to fall back on (V20261215000100, 01 BR-03, C21): a level with missing data is skipped and reported as a warning,
 * and when no carton is measured the line is {@link PackagingSpec.Status#MISSING}, as 09 §4 step 129 lists it. The
 * caller decides what that means (the estimate service answers 422 {@code LOAD_ESTIMATE_PACKAGING_MISSING} with a link
 * to the item). Nothing is defaulted.</p>
 *
 * <p>Cartons are {@code ceil(quantity / factor)}: the full ones, and a remainder as one more carton of the same
 * size weighing the full carton's weight pro rata (LP2-4). The result depends only on the data read, never on time or
 * randomness, so the same input gives the same answer. Reads join the caller's transaction.</p>
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

    /**
     * One carton of the packing unit {@code uomConversionId} of {@code sku}, as its unit of measure declares it (SCRUM-287
     * PR T, LP2-8): an unopened stock carton shipped as it is starts from these measures - the packer still weighs and
     * measures it. Empty when the unit is not the SKU's or lacks a full size or weight (nothing is defaulted, LP2-3);
     * the rotatable flag is the item's.
     */
    java.util.Optional<PackagingSpec.CartonSpec> unitCarton(Sku sku, UUID uomConversionId);
}
