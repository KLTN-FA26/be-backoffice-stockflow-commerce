package com.stockflow.product.internal.controller.dto;

import com.stockflow.product.api.TaxClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code PUT /api/v1/products/{productId}/tax-class} (WF-05 D-8). */
@Schema(description = "Set or change a product's VAT group: the class, why, and the version the screen read")
public record ChangeTaxClassRequest(

        @Schema(description = "STANDARD, REDUCED or EXEMPT")
        @NotNull(message = "taxClass is required")
        TaxClass taxClass,

        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason,

        @Schema(description = "The product's version as last read; a changed product answers 409 OPTIMISTIC_LOCK")
        @NotNull(message = "version is required")
        Long version
) {
}
