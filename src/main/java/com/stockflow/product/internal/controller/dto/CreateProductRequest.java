package com.stockflow.product.internal.controller.dto;

import com.stockflow.product.api.StorageClass;
import com.stockflow.product.api.TaxClass;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Request body for creating a product master row.
 *
 * <p>{@code categoryId} is not {@code @NotNull}: a product can be created without one, and
 * {@code submit()} (SCRUM-57) is what refuses to move it to approval without a category. Requiring
 * it here too would duplicate that rule at two layers that could silently drift apart.</p>
 */
@Schema(description = "Create a product master row")
public record CreateProductRequest(

        @Schema(example = "SOFA-3S-GREY")
        @NotBlank(message = "code is required")
        @Pattern(regexp = "[A-Za-z0-9\\-]{2,64}", message = "malformed code")
        String code,

        @NotBlank(message = "name is required")
        String name,

        @NotBlank(message = "nameEn is required")
        String nameEn,

        UUID categoryId,

        String description,

        String descriptionEn,

        @NotBlank(message = "brand is required")
        String brand,

        @NotNull(message = "taxClass is required")
        TaxClass taxClass,

        boolean customizable,

        @Schema(description = "Media gallery, in display order")
        List<String> images,

        @Schema(description = "SCRUM-74: shipping weight, once known")
        @Positive(message = "weightKg must be positive")
        BigDecimal weightKg,

        @Positive(message = "lengthCm must be positive")
        BigDecimal lengthCm,

        @Positive(message = "widthCm must be positive")
        BigDecimal widthCm,

        @Positive(message = "heightCm must be positive")
        BigDecimal heightCm,

        @Schema(description = "SCRUM-75: the shipped package - distinct from the product's own "
                + "dimensions above, since flat-pack furniture ships smaller than assembled")
        @Positive(message = "packageWeightKg must be positive")
        BigDecimal packageWeightKg,

        @Positive(message = "packageLengthCm must be positive")
        BigDecimal packageLengthCm,

        @Positive(message = "packageWidthCm must be positive")
        BigDecimal packageWidthCm,

        @Positive(message = "packageHeightCm must be positive")
        BigDecimal packageHeightCm,

        @Schema(description = "How many separate boxes one unit ships as")
        @Positive(message = "packageCount must be positive")
        Integer packageCount,

        @Schema(description = "SCRUM-76: carrier-facing shipping restrictions")
        boolean hazmat,

        boolean oversized, StorageClass storageClass,

        boolean requiresAdultSignature,

        @Size(max = 500, message = "shippingRestrictionNote must be at most 500 characters")
        String shippingRestrictionNote
) {
}
