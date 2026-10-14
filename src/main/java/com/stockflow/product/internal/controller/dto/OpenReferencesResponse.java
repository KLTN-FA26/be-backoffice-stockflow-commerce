package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Response of {@code GET /api/v1/products/{productId}/open-references}. */
@Schema(name = "OpenReferences", description = "Open purchase order lines and order lines that still refer to the product's SKUs")
public record OpenReferencesResponse(

        @Schema(description = "At most 20, taken from each kind in turn")
        List<OpenReferenceResponse> references,

        @Schema(description = "There are more than listed")
        boolean truncated
) {
}
