package com.stockflow.inventory.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/** An inventory item as the back office edits it: logistics, stock policy, and what the policy says about stock now. */
@Schema(name = "InventoryItem")
public record InventoryItemResponse(UUID id, String sku, long version, Logistics logistics, Policy policy,
                                    Evaluation evaluation) {

    public record Logistics(String unitOfMeasure, String barcode, BigDecimal weightKg, BigDecimal lengthCm,
                            BigDecimal widthCm, BigDecimal heightCm, BigDecimal packageWeightKg,
                            BigDecimal packageLengthCm, BigDecimal packageWidthCm, BigDecimal packageHeightCm,
                            int packageCount, int packSize, String storageClass, boolean requiresAdultSignature,
                            String shippingRestrictionNote, boolean qcRequired,
                            @Schema(description = "Packages may be turned 6 ways when a truck is loaded (docs 01, E13)")
                            boolean rotatable) {
    }

    public record Policy(Integer reorderPoint, Integer safetyStock, Integer maxQty, String removalStrategy,
                         String trackingMode, boolean expiryTracked, Integer maxShelfLifeDays) {
    }

    public record Evaluation(long usableOnHand, Integer reorderPoint, Integer safetyStock, Boolean reorderRequired,
                             Boolean belowSafetyStock) {
    }
}
