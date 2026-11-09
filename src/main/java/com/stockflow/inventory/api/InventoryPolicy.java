package com.stockflow.inventory.api;

/**
 * SKU-wide stock policy: how the SKU is tracked.
 *
 * <p>No thresholds (reorder point, safety stock, order-up-to level) and no expiry settings: the module raises no stock
 * alert, a shortfall is answered by a production order (kltn-docs 20 BR-13), and cups and packaging have no expiry
 * date (01 BR-17, 03 BR-06). Those columns are dropped by V20261111000300. {@code removalStrategy} no longer steers
 * allocation, which is FIFO by receipt time for every SKU (01 BR-17, 07 BR-03); it goes with the rest of the
 * configurable policy in team plan 5-5.</p>
 */
public record InventoryPolicy(RemovalStrategy removalStrategy, TrackingMode trackingMode) {}
