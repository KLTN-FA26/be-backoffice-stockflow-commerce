package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** One unit the variant is sold in. No price: there is none on a variant (kltn-docs 13 BR-04, 5-3). */
public record SalesUnitResponse(
        @Schema(description = "Null for the implicit default (no unit listed: the SKU's carton)")
        UUID id,
        UUID variantId,
        UUID uomConversionId,
        String unitOfMeasure,
        int factor,
        @Schema(description = "Always false: the base unit is never a sales unit (kltn-docs 01 BR-11)")
        boolean base,
        boolean active,
        @Schema(description = "True when nothing is listed and the variant is sold by its carton by default")
        boolean implicitDefault,
        Long version
) {
}
