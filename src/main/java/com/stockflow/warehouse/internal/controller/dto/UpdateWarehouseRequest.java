package com.stockflow.warehouse.internal.controller.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * No {@code prefix} and no {@code mapUnit}: both are fixed at registration (BR-13).
 *
 * @param version the version the edit was based on, from the last read
 */
public record UpdateWarehouseRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 500) String address,
        @Size(max = 500) String returnAddress,
        @NotNull @Positive @Digits(integer = 7, fraction = 3) BigDecimal mapWidth,
        @NotNull @Positive @Digits(integer = 7, fraction = 3) BigDecimal mapHeight,
        @PositiveOrZero long version
) {
}
