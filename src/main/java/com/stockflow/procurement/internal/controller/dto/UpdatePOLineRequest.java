package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record UpdatePOLineRequest(
        @Schema(description = "A line the draft has (its SKU cannot change); omit to add a line")
        UUID lineId,
        @NotBlank(message = "sku is required") @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}") String sku,
        @Size(max = 255) String description,
        @Positive(message = "quantityOrdered must be positive") @Max(1_000_000) int quantityOrdered,
        @Positive(message = "unitPrice must be positive")
                @NotNull
                @Digits(integer = 16, fraction = 2)
                BigDecimal unitPrice,
        @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal taxRate) {}
