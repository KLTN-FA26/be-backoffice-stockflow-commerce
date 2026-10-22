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
 * Changes a vehicle type: every field but the code. Setting {@code status} to INACTIVE retires the type.
 *
 * @param code    optional; a type's code never changes, so only its current code (any case) is accepted
 * @param gussetMm optional: omitted keeps the current clearance
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
        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("100000") @Digits(integer = 6, fraction = 3)
        BigDecimal maxPayloadKg,
        @NotNull @Min(0) @Max(10000) Integer availableCount,
        @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal rentalPrice,
        @Pattern(regexp = "^(ACTIVE|INACTIVE)$") String status,
        @NotNull Long version) {
}
