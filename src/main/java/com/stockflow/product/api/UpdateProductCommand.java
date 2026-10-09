package com.stockflow.product.api;

import java.util.UUID;

/** Input to {@link ProductService#update}. {@code code} is not here — it is fixed at creation. */
public record UpdateProductCommand(
        UUID productId,
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
