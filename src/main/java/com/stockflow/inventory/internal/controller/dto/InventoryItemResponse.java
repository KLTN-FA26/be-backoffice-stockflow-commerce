package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * An inventory item as the back office edits it: its logistics. There are no stock thresholds and no expiry settings
 * (the module raises no stock alert and tracks no expiry, kltn-docs 20 BR-13, 01 BR-17), and no stock policy: every SKU
 * is lot-tracked, without serial numbers, first in first out (01 BR-17, team plan 5-5). There is no weight or size
 * either: those are the carton's, on the item's carton unit (01 BR-03, V20261215000100).
 */
@Schema(name = "InventoryItem")
public record InventoryItemResponse(UUID id, String sku, long version, Logistics logistics) {

    public record Logistics(String unitOfMeasure, String barcode,
                            @Schema(description = "Packages may be turned 6 ways when a truck is loaded (docs 01, E13), "
                                    + "and a carton turned on the horizontal to fit a bin (06 BR-01)")
                            boolean rotatable) {
    }
}
