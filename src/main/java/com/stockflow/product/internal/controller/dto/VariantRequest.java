package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** A variant to add, or the new state of one. The SKU can change only while the variant is DRAFT. */
public record VariantRequest(
        @Schema(example = "CUP-12OZ-WHITE")
        @NotBlank(message = "sku is required")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}",
                message = "sku is 1-64 letters, digits, '.', '_' or '-', starting with a letter or digit")
        String sku,

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Schema(description = "The attribute combination, e.g. COLOR=WHITE;SIZE=12OZ; unique per product. "
                + "Absent: SKU=<sku>.")
        @Size(max = 512, message = "attributeSignature must be at most 512 characters")
        String attributeSignature,

        @PositiveOrZero(message = "position must not be negative")
        Integer position
) {
}
