package com.stockflow.warehouse.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/**
 * A rectangle on the map, in the warehouse's map unit. {@code (x, y)} is the top-left corner after
 * rotation; {@code rotation} is 0, 90, 180 or 270 (checked by the domain, a 400 otherwise).
 */
@Schema(description = "A rectangle in the map unit; (x, y) is its top-left corner after rotation. A bin's "
        + "footprint is relative to its shelf, before the shelf's rotation")
public record FootprintRequest(
        @Schema(example = "5")
        @NotNull @PositiveOrZero @Digits(integer = 7, fraction = 3) BigDecimal x,
        @Schema(example = "5")
        @NotNull @PositiveOrZero @Digits(integer = 7, fraction = 3) BigDecimal y,
        @Schema(example = "10")
        @NotNull @Positive @Digits(integer = 7, fraction = 3) BigDecimal width,
        @Schema(example = "1.2")
        @NotNull @Positive @Digits(integer = 7, fraction = 3) BigDecimal length,
        @Schema(description = "0, 90, 180 or 270", example = "0")
        @NotNull Integer rotation
) {
}
