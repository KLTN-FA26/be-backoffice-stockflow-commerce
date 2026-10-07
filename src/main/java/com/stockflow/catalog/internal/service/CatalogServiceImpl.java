package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.product.api.ProductPublication;
import com.stockflow.product.api.ProductStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Checkout-facing catalog API. Price validation joins the order transaction so a concurrent
 * base-price edit or unpublication cannot change the decision while stock is being reserved.
 */
@Service
@Transactional
class CatalogServiceImpl implements CatalogService {

    private final ListingRepository listings;
    private final ProductPublication products;

    CatalogServiceImpl(ListingRepository listings, ProductPublication products) {
        this.listings = listings;
        this.products = products;
    }

    /** Joins checkout; base-price and publication changes acquire the same product locks. */
    public Map<String, Money> checkoutPrices(Set<String> skus) {
        Map<UUID, Set<String>> grouped = new TreeMap<>();
        for (String sku : skus) {
            var id =
                    listings.publishedProductForSku(sku)
                            .orElseThrow(
                                    () -> new BusinessException(ErrorCode.PRODUCT_NOT_PURCHASABLE));
            grouped.computeIfAbsent(id, ignored -> new HashSet<>()).add(sku);
        }
        Map<String, Money> prices = new HashMap<>();
        // Stable lock order prevents two baskets from locking products in opposite order.
        for (var group : grouped.entrySet()) {
            var product = products.lock(group.getKey());
            if (product.status() != ProductStatus.PUBLISHED
                    || !listings.find(group.getKey()).map(l -> l.enabled()).orElse(false))
                throw new BusinessException(ErrorCode.PRODUCT_NOT_PURCHASABLE);
            for (String sku : group.getValue()) {
                if (product.skus().stream().noneMatch(s -> s.sku().equals(sku)))
                    throw new BusinessException(ErrorCode.PRODUCT_NOT_PURCHASABLE);
                var price =
                        listings.price(sku)
                                .orElseThrow(
                                        () -> new BusinessException(ErrorCode.PRICE_NOT_AVAILABLE));
                prices.put(sku, new Money(price.amount(), Money.VND));
            }
        }
        return Map.copyOf(prices);
    }
}
