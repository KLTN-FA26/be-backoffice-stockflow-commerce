package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.util.List;
import java.util.UUID;

/**
 * Open the inventory item of each customer SKU an order names (owner decision Q5 of 2026-11-26).
 *
 * @param items one per SKU; a SKU named twice must name the same variant
 */
public record OpenInventoryItemsCommand(List<Item> items) {

    public OpenInventoryItemsCommand {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * @param sku       the customer's SKU
     * @param variantId the catalogue variant it was drawn on ({@code CustomerSku.sourceVariantId}): whose cartons the
     *                  item is packed in, recorded on the item for good ({@code inventory_items.variant_id})
     */
    public record Item(Sku sku, UUID variantId) {
    }
}
