package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/** Sell the variant in one of its inventory item's units (docs 01 BR-11). */
public record CreateSalesUnitRequest(
        @NotNull(message = "uomConversionId is required")
        UUID uomConversionId,

        @Schema(description = "Whole VND per sales unit; required for a packing unit, absent for the base unit "
                + "(sold at the catalog base price)", example = "450000")
        BigDecimal price
) {
}
