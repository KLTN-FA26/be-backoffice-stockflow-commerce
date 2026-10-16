package com.stockflow.inventory.api;

/**
 * SKU-wide policy; null thresholds mean unconfigured, zero remains a real threshold.
 *
 * <p>{@code maxQty} is the order-up-to level: the quantity a replenishment brings the SKU back to, so a
 * suggested purchase is {@code maxQty} minus what is on hand and on order. {@code inventory_items.min_qty}
 * is deliberately not mapped here: it is superseded by {@code safetyStock} and {@code reorderPoint}, and saving a
 * policy sets it NULL so that its check against {@code max_qty} can never refuse a save.</p>
 */
public record InventoryPolicy(Integer reorderPoint, Integer safetyStock, Integer maxQty,
                              RemovalStrategy removalStrategy, TrackingMode trackingMode,
                              boolean expiryTracked, Integer maxShelfLifeDays) {}
