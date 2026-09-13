package com.stockflow.product.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/v1/products/{productId}/rejection}. */
public record RejectProductRequest(

        @NotBlank
        String reason
) {
}
