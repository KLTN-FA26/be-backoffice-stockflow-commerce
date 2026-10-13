package com.stockflow.product.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdateAttributeValueRequest(
        @NotNull(message = "version is required")
        Long version,

        @NotBlank(message = "label is required")
        @Size(max = 255, message = "label must be at most 255 characters")
        String label,

        BigDecimal valueNumber,

        String hexColor,

        Integer sortOrder,

        @NotNull(message = "active is required")
        Boolean active
) {
}
