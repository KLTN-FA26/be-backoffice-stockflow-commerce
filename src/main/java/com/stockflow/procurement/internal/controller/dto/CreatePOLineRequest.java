package com.stockflow.procurement.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record CreatePOLineRequest(

        @NotBlank(message = "sku is required")
        String sku,

        String description,

        @Positive(message = "quantityOrdered must be positive")
        int quantityOrdered,

        @PositiveOrZero(message = "unitPrice must not be negative")
        BigDecimal unitPrice
) {
}
