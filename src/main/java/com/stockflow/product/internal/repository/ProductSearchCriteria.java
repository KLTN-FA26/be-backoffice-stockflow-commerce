package com.stockflow.product.internal.repository;

import com.stockflow.product.api.ProductStatus;

import java.util.List;

/** Filter for {@link ProductSearchRepository#search}. Public: {@code internal.service} builds it. */
public record ProductSearchCriteria(String search, List<ProductStatus> statuses) {
}
