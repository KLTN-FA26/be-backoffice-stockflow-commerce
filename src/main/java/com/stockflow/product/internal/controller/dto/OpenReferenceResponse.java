package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** One open document that still refers to a SKU of the product. */
@Schema(name = "OpenReference", description = "An open document that still refers to a SKU of the product")
public record OpenReferenceResponse(

        @Schema(description = "PRODUCTION_ORDER_LINE or SALES_ORDER_LINE", example = "PRODUCTION_ORDER_LINE")
        String kind,

        @Schema(example = "PRD-20261014-0001")
        String documentNumber,

        UUID documentId,
        String sku
) {
}
