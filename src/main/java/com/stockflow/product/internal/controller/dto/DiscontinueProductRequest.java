package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/v1/products/{productId}/discontinuation} (SCRUM-461). */
@Schema(description = "Discontinue a product: why, and the version the screen read")
public record DiscontinueProductRequest(

        @Schema(description = "Why the product is discontinued (kltn-docs 01 §5.2); kept in the product and its history")
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason,

        @Schema(description = "The product's version as last read; a changed product answers 409 OPTIMISTIC_LOCK")
        @NotNull(message = "version is required")
        Long version
) {
}
