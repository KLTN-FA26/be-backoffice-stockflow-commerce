package com.stockflow.warehouse.internal.controller.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * A rectangle on the map, in the warehouse's map unit. {@code (x, y)} is the top-left corner after
 * rotation; {@code rotation} is 0, 90, 180 or 270 (checked by the domain, a 400 otherwise).
 */
public record FootprintRequest(
        @NotNull @PositiveOrZero @Digits(integer = 7, fraction = 3) BigDecimal x,
        @NotNull @PositiveOrZero @Digits(integer = 7, fraction = 3) BigDecimal y,
        @NotNull @Positive @Digits(integer = 7, fraction = 3) BigDecimal width,
        @NotNull @Positive @Digits(integer = 7, fraction = 3) BigDecimal length,
        @NotNull Integer rotation
) {
}
