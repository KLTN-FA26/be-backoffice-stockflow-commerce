package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * An inventory item as the back office edits it: logistics and stock policy. There are no stock thresholds and no
 * expiry settings: the module raises no stock alert and tracks no expiry (kltn-docs 20 BR-13, 01 BR-17).
 */
@Schema(name = "InventoryItem")
public record InventoryItemResponse(UUID id, String sku, long version, Logistics logistics, Policy policy) {

    public record Logistics(String unitOfMeasure, String barcode, BigDecimal weightKg, BigDecimal lengthCm,
                            BigDecimal widthCm, BigDecimal heightCm, BigDecimal packageWeightKg,
                            BigDecimal packageLengthCm, BigDecimal packageWidthCm, BigDecimal packageHeightCm,
                            int packageCount, int packSize, String storageClass, boolean requiresAdultSignature,
                            String shippingRestrictionNote,
                            @Schema(description = "Packages may be turned 6 ways when a truck is loaded (docs 01, E13)")
                            boolean rotatable) {
    }

    public record Policy(String removalStrategy, String trackingMode) {
    }
}
