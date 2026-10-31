package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "RevisionChange", description = "One difference between a revision and the one it is compared with")
public record RevisionChangeResponse(
        @Schema(description = "LINE or HEADER") String scope,
        @Schema(description = "Null for a header change") Integer lineNo,
        String sku,
        @Schema(description = "CHANGED, REMOVED or ADDED") String type,
        @Schema(description = "quantity, unitPrice, taxRate or description on a line; expectedDate, note or"
                + " totalAmount on the header; null for REMOVED and ADDED") String field,
        @Schema(description = "A removed line's quantity") String before,
        @Schema(description = "An added line's quantity") String after) {}
