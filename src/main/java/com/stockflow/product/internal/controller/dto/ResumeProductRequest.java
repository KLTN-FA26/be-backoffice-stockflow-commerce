package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /api/v1/products/{productId}/resumption} (SCRUM-461). */
@Schema(description = "Resume a suspended product: the version the screen read")
public record ResumeProductRequest(

        @Schema(description = "The product's version as last read; a changed product answers 409 OPTIMISTIC_LOCK")
        @NotNull(message = "version is required")
        Long version
) {
}
