package com.stockflow.warehouse.internal.controller.dto;

import com.stockflow.warehouse.internal.domain.MapUnit;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** {@code prefix} may be sent in lower case; it is stored upper-cased and never changes. */
public record RegisterWarehouseRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9]{1,10}") String prefix,
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 500) String address,
        @Size(max = 500) String returnAddress,
        @NotNull MapUnit mapUnit,
        @NotNull @Positive @Digits(integer = 7, fraction = 3) BigDecimal mapWidth,
        @NotNull @Positive @Digits(integer = 7, fraction = 3) BigDecimal mapHeight
) {
}
