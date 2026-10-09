package com.stockflow.catalog.internal.controller.dto;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
public record SetBasePriceRequest(@NotNull @Min(0) Long revision,
        @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=16,fraction=2) BigDecimal price,
        @NotBlank @Pattern(regexp="VND") String currency) {}
