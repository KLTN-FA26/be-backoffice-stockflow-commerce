package com.stockflow.warehouse.internal.controller.dto;

import com.stockflow.warehouse.internal.domain.MapUnit;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** {@code prefix} may be sent in lower case; it is stored upper-cased and never changes. */
@Schema(description = "Register a warehouse with an empty map")
public record RegisterWarehouseRequest(
        @Schema(description = "Upper-cased. Starts every location code of the warehouse and never changes",
                example = "HCM")
        @NotBlank @Pattern(regexp = "[A-Za-z0-9]{1,10}") String prefix,
        @Schema(example = "Ho Chi Minh warehouse")
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 500) String address,
        @Size(max = 500) String returnAddress,
        @Schema(description = "Only M (metres) for now; never changes", example = "M")
        @NotNull MapUnit mapUnit,
        @Schema(example = "60")
        @NotNull @Positive @Digits(integer = 7, fraction = 3) BigDecimal mapWidth,
        @Schema(example = "40")
        @NotNull @Positive @Digits(integer = 7, fraction = 3) BigDecimal mapHeight
) {
}
