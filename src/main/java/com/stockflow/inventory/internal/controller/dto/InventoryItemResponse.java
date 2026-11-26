package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * An inventory item as the back office edits it: its logistics. There are no stock thresholds and no expiry settings
 * (the module raises no stock alert and tracks no expiry, kltn-docs 20 BR-13, 01 BR-17), and no stock policy: every SKU
 * is lot-tracked, without serial numbers, first in first out (01 BR-17, team plan 5-5). There is no weight or size
 * either: those are the carton's, and the carton is the catalogue variant's (01 BR-03, V20261215000100, owner decision
 * Q1).
 */
@Schema(name = "InventoryItem")
public record InventoryItemResponse(UUID id, String sku, long version, Logistics logistics) {

    /**
     * Whether a carton may be turned is no longer here: it is the catalogue variant's ({@code rotatable} on the
     * variant, owner decision Q9, V20261231000800), like the carton itself.
     */
    public record Logistics(String unitOfMeasure, String barcode) {
    }

}
