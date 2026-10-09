package com.stockflow.inventory.api;

/** SKU-wide policy; null thresholds mean unconfigured, zero remains a real threshold. */
public record InventoryPolicy(Integer reorderPoint, Integer safetyStock, RemovalStrategy removalStrategy,
                              TrackingMode trackingMode, boolean expiryTracked, Integer maxShelfLifeDays) {}
