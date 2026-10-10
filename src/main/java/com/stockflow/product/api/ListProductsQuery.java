package com.stockflow.product.api;

import java.util.List;
import java.util.UUID;

/**
 * Input to {@link ProductService#list}.
 *
 * @param search     free-text match against name/code (see {@code common.persistence.Specs#contains})
 * @param categoryId only products whose primary category this is; null for all
 * @param brandId    only products of this brand; null for all
 * @param sort       {@code property,direction} pairs; see {@code common.persistence.SortWhitelist}
 */
public record ListProductsQuery(
        int page,
        int size,
        String search,
        List<ProductStatus> statuses,
        UUID categoryId,
        UUID brandId,
        String sort
) {
}
