package com.stockflow.catalog.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * A customer's own price for one SKU: quantity tiers (from {@code minQuantity} units on, {@code unitPrice}
 * whole VND). {@code version} is required to replace an existing price, absent to create one.
 */
public record CustomerPriceRequest(
        @NotEmpty @Size(max = 20) List<@Valid Tier> tiers,
        @Size(max = 500) String note,
        @PositiveOrZero Long version
) {
    public record Tier(@Min(1) int minQuantity, @NotNull BigDecimal unitPrice) {
    }
}
