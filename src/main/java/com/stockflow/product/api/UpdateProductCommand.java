package com.stockflow.product.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Input to {@link ProductService#update}. {@code code} is not here — it is fixed at creation. */
public record UpdateProductCommand(
        UUID productId,
        String name,
        String nameEn,
        UUID categoryId,
        String description,
        String descriptionEn,
        String brand,
        TaxClass taxClass,
        boolean customizable,
        List<String> images,
        BigDecimal weightKg,
        BigDecimal lengthCm,
        BigDecimal widthCm,
        BigDecimal heightCm,
        BigDecimal packageWeightKg,
        BigDecimal packageLengthCm,
        BigDecimal packageWidthCm,
        BigDecimal packageHeightCm,
        Integer packageCount,
        boolean hazmat,
        boolean oversized, StorageClass storageClass,
        boolean requiresAdultSignature,
        String shippingRestrictionNote
) {
}
