package com.stockflow.product.internal.controller.dto;

import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.TaxClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Request body for updating a product master row. No {@code code}: it is fixed at creation. */
@Schema(description = "Update a product master row")
public record UpdateProductRequest(

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @NotBlank(message = "nameEn is required")
        @Size(max = 300, message = "nameEn must be at most 300 characters")
        String nameEn,

        UUID brandId,

        UUID categoryId,

        String shortDescription,

        String description,

        String descriptionEn,

        @NotNull(message = "taxClass is required")
        TaxClass taxClass,

        ProductKind kind
) {
}
