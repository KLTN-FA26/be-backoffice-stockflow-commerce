package com.stockflow.product.internal.controller.dto;

import com.stockflow.product.api.TaxClass;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
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

        @Schema(description = "SCRUM-74: shipping weight/dimensions, null until known.")
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,

        @Schema(description = "SCRUM-75: the shipped package - null until known.")
        BigDecimal packageWeightKg,
        BigDecimal packageLengthCm,
        BigDecimal packageWidthCm,
        BigDecimal packageHeightCm,
        Integer packageCount,

        @Schema(description = "SCRUM-76: carrier-facing shipping restrictions.")
        boolean hazmat,
        boolean oversized,
        boolean requiresAdultSignature,
        String shippingRestrictionNote
) {
}
