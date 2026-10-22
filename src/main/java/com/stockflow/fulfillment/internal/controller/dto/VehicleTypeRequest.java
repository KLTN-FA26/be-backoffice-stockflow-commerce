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
 * Declares a vehicle type (a truck or pickup; the ISO containers and common trucks are seeded as presets) - kltn-docs 09 §3.
 *
 * @param code            2-32 upper-case letters, digits, '-' or '_' (lower case is upper-cased); never changes
 * @param kind            CONTAINER, TRUCK or PICKUP
 * @param gussetMm        ceiling-corner clearance, mm kept free in each upper corner (BR-10); optional, default 0
 * @param innerLengthMm   inner cargo space, mm (1..30000)
 * @param maxPayloadKg    maximum payload, kg (more than 0, at most 100000)
 * @param availableCount  how many vehicles of this type a plan may use (BR-12)
 * @param rentalPrice     reference rental price (VND), optional: the planner does not choose by price
 * @param status          ACTIVE (default) or INACTIVE: an inactive type is not offered to new plans
 */
public record VehicleTypeRequest(
        @NotBlank @Size(max = 32) @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$") String code,
        @NotBlank @Size(max = 120) String name,
        @NotNull @Pattern(regexp = "^(CONTAINER|TRUCK|PICKUP)$") String kind,
        @NotNull @Min(1) @Max(30000) Integer innerLengthMm,
        @NotNull @Min(1) @Max(30000) Integer innerWidthMm,
        @NotNull @Min(1) @Max(30000) Integer innerHeightMm,
        @Min(0) @Max(1000) Integer gussetMm,
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100000") @Digits(integer = 6, fraction = 3)
        BigDecimal maxPayloadKg,
        @NotNull @Min(0) @Max(10000) Integer availableCount,
        @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal rentalPrice,
        @Pattern(regexp = "^(ACTIVE|INACTIVE)$") String status) {
}
