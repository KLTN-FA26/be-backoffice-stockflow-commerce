package com.stockflow.inventory.api;

/**
 * Version and physical unit belong to the inventory item, independently of the product. No stock policy: every SKU is
 * lot-tracked, without serial numbers, first in first out (kltn-docs 01 BR-17, team plan 5-5).
 */
public record InventoryControlItem(String sku, String unitOfMeasure, long version) {}
