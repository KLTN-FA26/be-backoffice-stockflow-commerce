package com.stockflow.inventory.api;

/** One inventory item's logistics, with the item version an edit must quote back. */
public record InventoryItemLogistics(String sku, long version, ItemLogistics logistics) {
}
