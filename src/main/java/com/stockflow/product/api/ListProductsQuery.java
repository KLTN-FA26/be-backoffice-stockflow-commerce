package com.stockflow.product.api;

import java.util.List;

/**
 * Input to {@link ProductService#list}.
 *
 * @param search free-text match against name/code (see {@code common.persistence.Specs#contains})
 * @param sort   {@code property,direction} pairs; see {@code common.persistence.SortWhitelist}
 */
public record ListProductsQuery(
        int page,
        int size,
        String search,
        List<ProductStatus> statuses,
        String sort
) {
}
