package com.stockflow.product.internal.repository;

import com.stockflow.product.api.ProductStatus;

import java.util.List;
import java.util.UUID;

public record ProductSearchCriteria(String search, List<ProductStatus> statuses, UUID categoryId, UUID brandId) {
}
