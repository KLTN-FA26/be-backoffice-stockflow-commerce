package com.stockflow.inventory.api;

/**
 * SKU-wide policy; null thresholds mean unconfigured, zero remains a real threshold.
 *
 * <p>{@code maxQty} is the order-up-to level: the quantity a replenishment brings the SKU back to, so a
 * suggested purchase is {@code maxQty} minus what is on hand and on order. The minimum threshold is
 * {@code reorderPoint} (kltn-docs 02 §4); the old {@code inventory_items.min_qty} is dropped (V20261019000500).</p>
 */
public record InventoryPolicy(Integer reorderPoint, Integer safetyStock, Integer maxQty,
                              RemovalStrategy removalStrategy, TrackingMode trackingMode,
                              boolean expiryTracked, Integer maxShelfLifeDays) {}
