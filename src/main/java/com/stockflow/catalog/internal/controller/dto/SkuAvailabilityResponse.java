package com.stockflow.catalog.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** One SKU's stock indicator on a catalog page. */
public record SkuAvailabilityResponse(
        @Schema(example = "SOFA-3S-GREY")
        String sku,

        @Schema(description = "IN_STOCK, LOW_STOCK or OUT_OF_STOCK", example = "LOW_STOCK")
        String availability
) {
}
