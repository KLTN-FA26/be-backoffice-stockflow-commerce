package com.stockflow.catalog.internal.controller.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * A customer group's price for one SKU, whole VND per piece from the first piece. {@code version} is required
 * to replace an existing price, absent to create one.
 */
public record SegmentPriceRequest(@NotNull BigDecimal price, @PositiveOrZero Long version) {
}
