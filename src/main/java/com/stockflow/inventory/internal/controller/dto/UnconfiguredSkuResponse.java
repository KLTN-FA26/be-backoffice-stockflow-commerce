package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "UnconfiguredSku", description = "A SKU with usable stock and no reorder point: no alert can be raised for it")
public record UnconfiguredSkuResponse(String sku, long usableOnHand, Integer safetyStock) {
}
