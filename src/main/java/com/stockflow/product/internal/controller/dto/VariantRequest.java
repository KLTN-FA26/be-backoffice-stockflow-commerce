package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** A variant to add, or the new state of one. The SKU can change only while the variant is DRAFT. */
public record VariantRequest(
        @Schema(example = "CUP-12OZ-WHITE")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}",
                message = "sku is 1-64 letters, digits, '.', '_' or '-', starting with a letter or digit")
        String sku,

        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Schema(description = "One dictionary value per axis of the product; the signature is derived from them.")
        List<UUID> optionValueIds,

        @PositiveOrZero(message = "position must not be negative")
        Integer position
) {
}
