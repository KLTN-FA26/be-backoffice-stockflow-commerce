package com.stockflow.catalog.api;

import java.util.List;

/**
 * One published product found by {@link CatalogSearch}.
 *
 * @param slug     the product's storefront address
 * @param variants its SKUs, each with its name
 */
public record CatalogMatch(String title, String slug, List<Variant> variants) {

    public CatalogMatch {
        variants = List.copyOf(variants);
    }

    public record Variant(String sku, String name) {
    }
}
