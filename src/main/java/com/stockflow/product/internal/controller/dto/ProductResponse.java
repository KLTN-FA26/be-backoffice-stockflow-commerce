package com.stockflow.product.internal.controller.dto;

import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.TaxClass;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/** What the API returns for one product master row. */
@Schema(name = "Product", description = "Product master row")
public record ProductResponse(

        UUID productId,

        @Schema(example = "CUP-12OZ")
        String code,

        String name,
        String nameEn,

        @Schema(description = "Storefront slug; edited with the selling content, fixed once ever published")
        String slug,

        UUID brandId,
        String brandName,

        @Schema(description = "The primary category")
        UUID categoryId,

        String shortDescription,
        String description,
        String descriptionEn,
        TaxClass taxClass,
        ProductKind kind,

        @Schema(example = "DRAFT")
        String status,

        Instant createdAt,
        String createdBy,
        Instant lastModifiedAt,
        String lastModifiedBy,

        @Schema(description = "Who submitted this product for approval, if it has been.")
        UUID submittedBy,
        Instant submittedAt,

        @Schema(description = "Who approved this product, if it has been.")
        UUID approvedBy,
        Instant approvedAt,

        @Schema(description = "Set when the last approval decision was a rejection.")
        String rejectionReason,

        Instant publishedAt,
        Instant discontinuedAt
) {
}
