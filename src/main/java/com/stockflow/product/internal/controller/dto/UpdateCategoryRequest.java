package com.stockflow.product.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateCategoryRequest(
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        Integer sortOrder,

        String imageUrl,

        @Size(max = 255, message = "seoTitle must be at most 255 characters")
        String seoTitle,

        String seoDescription,

        @NotNull(message = "active is required")
        Boolean active
) {
}
