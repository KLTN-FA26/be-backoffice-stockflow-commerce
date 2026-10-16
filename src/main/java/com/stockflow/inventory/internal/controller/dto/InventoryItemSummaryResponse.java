package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** One row of the inventory item list. */
@Schema(name = "InventoryItemSummary")
public record InventoryItemSummaryResponse(UUID id, String sku, String unitOfMeasure, String barcode,
                                           String storageClass, String trackingMode, boolean policyConfigured) {
}
