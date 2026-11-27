package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * The unit the variant is sold in: its carton (kltn-docs 01 §3 "Đơn vị bán", BR-11). No price: there is none on a
 * variant (kltn-docs 13 BR-04, 5-3).
 */
public record SalesUnitResponse(
        UUID variantId,
        @Schema(description = "The carton unit of the SKU's carton specification")
        UUID uomConversionId,
        @Schema(example = "THUNG")
        String unitOfMeasure,
        @Schema(description = "Pieces in one carton", example = "1000")
        int factor
) {
}
