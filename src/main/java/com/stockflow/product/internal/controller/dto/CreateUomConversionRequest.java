package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Declare a packing unit of a SKU (docs 01 §3 "Đơn vị quy đổi", BR-09). */
public record CreateUomConversionRequest(
        @Schema(example = "CARTON")
        @NotNull(message = "unitOfMeasure is required")
        @Pattern(regexp = "[A-Z0-9_]{1,16}", message = "unitOfMeasure is 1-16 upper-case letters, digits or _")
        String unitOfMeasure,

        @Schema(description = "How many base units one of this unit holds", example = "1000")
        @NotNull(message = "factor is required") @Min(value = 1, message = "factor must be at least 1")
        Integer factor,

        @Positive(message = "weightKg must be positive") BigDecimal weightKg,
        @Positive(message = "lengthCm must be positive") BigDecimal lengthCm,
        @Positive(message = "widthCm must be positive") BigDecimal widthCm,
        @Positive(message = "heightCm must be positive") BigDecimal heightCm,

        @Schema(description = "Make it the default purchase unit (unmarks the previous one); false when absent")
        Boolean defaultPurchase,

        @Schema(description = "Make it the default shipping unit of the item, the carton load planning estimates "
                + "with: unmarks the previous one in the same transaction; false when absent; not on a retired unit")
        Boolean defaultShipping
) {
}
