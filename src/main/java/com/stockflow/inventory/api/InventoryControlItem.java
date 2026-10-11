package com.stockflow.inventory.api;

/** Version and physical unit belong to the inventory item, independently of the product. */
public record InventoryControlItem(
        String sku, String unitOfMeasure, long version, InventoryPolicy policy) {}
