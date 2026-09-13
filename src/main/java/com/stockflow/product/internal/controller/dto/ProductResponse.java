package com.stockflow.product.internal.controller.dto;

import com.stockflow.product.api.TaxClass;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What the API returns for one product master row. */
@Schema(name = "Product", description = "Product master row")
public record ProductResponse(

        UUID productId,

        @Schema(example = "SOFA-3S-GREY")
        String code,

        String name,
        String nameEn,
        UUID categoryId,
        String description,
        String descriptionEn,
        String brand,
        TaxClass taxClass,
        boolean customizable,

        @Schema(description = "Media gallery, in display order. Empty on a list row - see the "
                + "docs on ProductSearchRepository for why.")
        List<String> images,

        @Schema(example = "DRAFT")
        String status,

        Instant createdAt,
        String createdBy
) {
}
