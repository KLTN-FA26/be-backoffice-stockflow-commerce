package com.stockflow.product.internal.controller.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Reprice a sales unit; same price rule as on create. */
public record UpdateSalesUnitRequest(
        @NotNull(message = "version is required") @Min(0)
        Long version,

        BigDecimal price
) {
}
