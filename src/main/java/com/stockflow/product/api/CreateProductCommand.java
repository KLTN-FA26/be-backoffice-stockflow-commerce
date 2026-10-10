package com.stockflow.product.api;

import java.util.UUID;

/**
 * Input to {@link ProductService#create}. The product is born with one default variant whose SKU is
 * the product code, and that variant with its inventory item; logistics live on the inventory item
 * and images on a variant, not here.
 */
public record CreateProductCommand(
        String code,
        String name,
        String nameEn,
        UUID brandId,
        UUID categoryId,
        String shortDescription,
        String description,
        String descriptionEn,
        TaxClass taxClass,
        ProductKind kind
) {
}
