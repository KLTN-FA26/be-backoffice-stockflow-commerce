package com.stockflow.fulfillment.internal.controller.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Changes a vehicle type as a full replace, like a carrier: every field but the code is sent; an optional field left out
 * is cleared ({@code rentalPrice} and the door to none, {@code gussetMm} to 0), and only a missing {@code status} keeps the current
 * one. A preset also keeps its {@code kind}. Setting {@code status} to INACTIVE retires the type.
 *
 * @param code    optional; a type's code never changes, so only its current code (any case) is accepted
 * @param gussetMm optional (edge in mm of the reinforcement cube the placement solver keeps free in the top corners - two cubes at the front-end top corners (z = 0), or, on a 45' box (inner length of 12 500 mm or more), four at the corner-post positions of the 40' post span instead of those two; 0 = none); omitted = 0
 * @param doorWidthMm  optional door opening width in mm (at most the inner width); omitted = cleared (no door limit)
 * @param doorHeightMm optional door opening height in mm (at most the inner height); omitted = cleared (no door limit)
 * @param version required: the version read (optimistic lock)
 * @see VehicleTypeRequest for the fields
 */
public record VehicleTypeUpdateRequest(
        @Size(max = 32) String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull @Pattern(regexp = "^(CONTAINER|TRUCK|PICKUP)$") String kind,
        @NotNull @Min(1) @Max(30000) Integer innerLengthMm,
        @NotNull @Min(1) @Max(30000) Integer innerWidthMm,
        @NotNull @Min(1) @Max(30000) Integer innerHeightMm,
        @Min(0) @Max(1000) Integer gussetMm,
        @Min(1) @Max(30000) Integer doorWidthMm,
        @Min(1) @Max(30000) Integer doorHeightMm,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100000") @Digits(integer = 6, fraction = 3)
        BigDecimal maxPayloadKg,
        @NotNull @Min(0) @Max(10000) Integer availableCount,
        @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal rentalPrice,
        @Pattern(regexp = "^(ACTIVE|INACTIVE)$") String status,
        @NotNull Long version) {
}
