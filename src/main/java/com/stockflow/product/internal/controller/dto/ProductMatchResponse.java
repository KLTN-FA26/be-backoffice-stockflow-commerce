package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/** A product that may be the same as the one being created, edited or shown (SCRUM-88). */
@Schema(name = "ProductMatch", description = "A possible duplicate: same brand and primary category, same name key")
public record ProductMatchResponse(
        UUID productId,

        @Schema(example = "CUP-12OZ")
        String code,

        String name,

        @Schema(example = "DRAFT")
        String status
) {
}
