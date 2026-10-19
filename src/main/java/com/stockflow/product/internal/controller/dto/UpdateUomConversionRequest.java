package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * PUT replaces a unit. On the base unit only {@code defaultPurchase} may change (send the rest as read);
 * {@code active=false} retires a packing unit (BR-10), which cannot then be the default purchase unit.
 */
public record UpdateUomConversionRequest(
        @NotNull(message = "version is required") @Min(0)
        Long version,

        @NotNull(message = "unitOfMeasure is required")
        @Pattern(regexp = "[A-Z0-9_]{1,16}", message = "unitOfMeasure is 1-16 upper-case letters, digits or _")
        String unitOfMeasure,

        @NotNull(message = "factor is required") @Min(value = 1, message = "factor must be at least 1")
        Integer factor,

        @Schema(description = "Ignored for the base unit, whose code, size and weight are the item's logistics")
        @Positive(message = "weightKg must be positive") BigDecimal weightKg,
        @Positive(message = "lengthCm must be positive") BigDecimal lengthCm,
        @Positive(message = "widthCm must be positive") BigDecimal widthCm,
        @Positive(message = "heightCm must be positive") BigDecimal heightCm,

        @NotNull(message = "defaultPurchase is required")
        Boolean defaultPurchase,

        @NotNull(message = "active is required")
        Boolean active
) {
}
