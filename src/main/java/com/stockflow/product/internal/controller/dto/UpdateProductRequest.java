package com.stockflow.product.internal.controller.dto;

import com.stockflow.product.api.TaxClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Request body for updating a product master row. No {@code code}: it is fixed at creation. */
@Schema(description = "Update a product master row")
public record UpdateProductRequest(

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "nameEn is required")
        String nameEn,

        UUID categoryId,

        String description,

        String descriptionEn,

        @NotBlank(message = "brand is required")
        String brand,

        @NotNull(message = "taxClass is required")
        TaxClass taxClass,

        boolean customizable,

        List<String> images,

        @Positive(message = "weightKg must be positive")
        BigDecimal weightKg,

        @Positive(message = "lengthCm must be positive")
        BigDecimal lengthCm,

        @Positive(message = "widthCm must be positive")
        BigDecimal widthCm,

        @Positive(message = "heightCm must be positive")
        BigDecimal heightCm
) {
}
