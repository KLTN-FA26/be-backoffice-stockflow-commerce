package com.stockflow.product.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What discontinuing a product answers: the product as it is now (its fields at the top level, as every
 * other product endpoint returns them) plus what the person should know. The discontinuation has
 * happened; nothing here is a refusal (kltn-docs 01 step 12).
 */
@Schema(name = "ProductDiscontinuation", description = "The discontinued product, with warnings about what still refers to it")
public record ProductDiscontinuationResponse(

        @JsonUnwrapped
        ProductResponse product,

        @Schema(description = "OPEN_PURCHASE_ORDER_LINES (goods on their way are still received) and/or "
                + "UNSHIPPED_ORDER_LINES (orders already placed carry on)")
        List<String> warnings,

        @Schema(description = "The open documents behind the warnings, at most 20")
        List<OpenReferenceResponse> openReferences,

        @Schema(description = "There are more open documents than listed")
        boolean openReferencesTruncated
) {
}
