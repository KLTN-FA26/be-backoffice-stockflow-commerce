package com.stockflow.inventory.api;

/** One inventory item's purchasing information, with the item version an edit must quote back. */
public record InventoryItemPurchasing(String sku, long version, ItemPurchasing purchasing) {
}
