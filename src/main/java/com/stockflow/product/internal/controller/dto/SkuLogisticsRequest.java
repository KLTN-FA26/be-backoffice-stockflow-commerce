package com.stockflow.product.internal.controller.dto;

import com.stockflow.inventory.api.StorageClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * PUT replaces the logistics of one SKU's inventory item (SCRUM-74/75/76, moved off the product row:
 * two variants of one product weigh and pack differently). {@code version} is the inventory item's,
 * as the last read returned it. A null measure means "not known yet".
 */
public record SkuLogisticsRequest(
        @NotNull(message = "version is required") @Min(0)
        Long version,

        @Schema(example = "EACH")
        @NotNull(message = "unitOfMeasure is required")
        @Pattern(regexp = "[A-Z0-9_]{1,16}", message = "unitOfMeasure is 1-16 upper-case letters, digits or _")
        String unitOfMeasure,

        @Size(min = 1, max = 64, message = "barcode must be 1-64 characters")
        String barcode,

        @Positive(message = "weightKg must be positive") BigDecimal weightKg,
        @Positive(message = "lengthCm must be positive") BigDecimal lengthCm,
        @Positive(message = "widthCm must be positive") BigDecimal widthCm,
        @Positive(message = "heightCm must be positive") BigDecimal heightCm,

        @Schema(description = "The shipped package, distinct from the unit's own size: cups ship nested")
        @Positive(message = "packageWeightKg must be positive") BigDecimal packageWeightKg,
        @Positive(message = "packageLengthCm must be positive") BigDecimal packageLengthCm,
        @Positive(message = "packageWidthCm must be positive") BigDecimal packageWidthCm,
        @Positive(message = "packageHeightCm must be positive") BigDecimal packageHeightCm,

        @Schema(description = "How many boxes one unit ships as; 1 when absent")
        @Min(value = 1, message = "packageCount must be at least 1") Integer packageCount,

        @Schema(description = "Units per inner pack; 1 when absent")
        @Min(value = 1, message = "packSize must be at least 1") Integer packSize,

        @NotNull(message = "storageClass is required")
        StorageClass storageClass,

        boolean requiresAdultSignature,

        @Size(max = 500, message = "shippingRestrictionNote must be at most 500 characters")
        String shippingRestrictionNote,

        @Schema(description = "Receiving sends this SKU through the QC area (3-step flow)")
        boolean qcRequired
) {
}
