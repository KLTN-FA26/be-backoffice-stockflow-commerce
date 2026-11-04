package com.stockflow.inventory.api;

/**
 * A SKU whose usable on-hand has reached a threshold: at or below its reorder point, or below its safety stock.
 * Usable on-hand is AVAILABLE stock that has not expired, reservations not subtracted (so it is not ATP).
 *
 * @param reorderRequired  at or below the reorder point; false when only the safety stock is crossed
 * @param belowSafetyStock strictly below the safety stock
 */
public record LowStockItem(String sku, long usableOnHand, Integer reorderPoint, Integer safetyStock,
                           boolean reorderRequired, boolean belowSafetyStock) {
}
