package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** One row of the inventory item list. No tracking mode: every SKU is lot-tracked (kltn-docs 01 BR-17). */
@Schema(name = "InventoryItemSummary")
public record InventoryItemSummaryResponse(UUID id, String sku, String unitOfMeasure, String barcode) {
}
