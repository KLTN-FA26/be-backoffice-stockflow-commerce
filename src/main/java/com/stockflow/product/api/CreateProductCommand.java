package com.stockflow.product.api;

import java.util.List;
import java.util.UUID;

/** Input to {@link ProductService#create}. */
public record CreateProductCommand(
        String code,
        String name,
        String nameEn,
        UUID categoryId,
        String description,
        String descriptionEn,
        String brand,
        TaxClass taxClass,
        boolean customizable,
        List<String> images
) {
}
