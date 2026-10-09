package com.stockflow.product.internal.controller.dto;

import java.util.UUID;
public record InventoryControlResponse(UUID skuId, String sku, String unitOfMeasure, long version,
        Integer reorderPoint, Integer safetyStock, String removalStrategy, String trackingMode,
        boolean expiryTracked, Integer maxShelfLifeDays, long usableOnHand,
        Boolean reorderRequired, Boolean belowSafetyStock) {}
