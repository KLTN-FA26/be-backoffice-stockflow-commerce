package com.stockflow.product.internal.controller.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Sell the variant in one of its inventory item's units (docs 01 BR-11). No price: a customer's SKU is sold by the
 * carton at a price per piece agreed on the quote, and there is no base price (kltn-docs 13 BR-04, team plan 5-3).
 */
public record CreateSalesUnitRequest(
        @NotNull(message = "uomConversionId is required")
        UUID uomConversionId
) {
}
