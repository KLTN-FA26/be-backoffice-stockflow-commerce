package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.api.CatalogService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkout-facing catalog API. Price validation joins the order transaction so a concurrent
 * base-price edit or unpublication cannot change the decision while stock is being reserved.
 */
@Service
@Transactional
class CatalogServiceImpl implements CatalogService {

    private final com.stockflow.catalog.internal.repository.ListingRepository listings;
    private final com.stockflow.product.api.ProductPublication products;
    CatalogServiceImpl(com.stockflow.catalog.internal.repository.ListingRepository listings,
                       com.stockflow.product.api.ProductPublication products) {
        this.listings = listings;
        this.products = products;
    }

    /** Joins checkout; base-price and publication changes acquire the same product locks. */
    public java.util.Map<String, com.stockflow.common.domain.Money> checkoutPrices(java.util.Set<String> skus) {
        java.util.Map<java.util.UUID, java.util.Set<String>> grouped = new java.util.TreeMap<>();
        for (String sku : skus) {
            var id = listings.publishedProductForSku(sku)
                    .orElseThrow(() -> new com.stockflow.common.error.BusinessException(com.stockflow.common.error.ErrorCode.CATALOG_NOT_READY));
            grouped.computeIfAbsent(id, ignored -> new java.util.HashSet<>()).add(sku);
        }
        java.util.Map<String, com.stockflow.common.domain.Money> prices = new java.util.HashMap<>();
        // Stable lock order prevents two baskets from locking products in opposite order.
        for (var group : grouped.entrySet()) {
            var product = products.lock(group.getKey());
            if (product.status() != com.stockflow.product.api.ProductStatus.PUBLISHED
                    || !listings.find(group.getKey()).map(l -> l.enabled()).orElse(false))
                throw new com.stockflow.common.error.BusinessException(com.stockflow.common.error.ErrorCode.CATALOG_NOT_READY);
            for (String sku : group.getValue()) {
                if (product.skus().stream().noneMatch(s -> s.sku().equals(sku)))
                    throw new com.stockflow.common.error.BusinessException(com.stockflow.common.error.ErrorCode.CATALOG_NOT_READY);
                var price = listings.price(sku).orElseThrow(() -> new com.stockflow.common.error.BusinessException(com.stockflow.common.error.ErrorCode.CATALOG_NOT_READY));
                prices.put(sku, new com.stockflow.common.domain.Money(price.amount(), com.stockflow.common.domain.Money.VND));
            }
        }
        return java.util.Map.copyOf(prices);
    }
}
