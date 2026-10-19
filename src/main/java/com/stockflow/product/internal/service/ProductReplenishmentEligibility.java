package com.stockflow.product.internal.service;

import com.stockflow.inventory.api.ReplenishmentEligibility;
import com.stockflow.product.internal.repository.ProductCommerceRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Inventory's question "may this SKU be bought again?" answered by the rule purchase orders use
 * ({@link com.stockflow.product.api.Purchasability}: an {@code ACTIVE} variant of an {@code APPROVED} or
 * {@code PUBLISHED} product), in one query, so a low-stock alert and a new purchase order line can never disagree
 * about the same SKU.
 */
@Component
class ProductReplenishmentEligibility implements ReplenishmentEligibility {

    private final ProductCommerceRepository products;

    ProductReplenishmentEligibility(ProductCommerceRepository products) {
        this.products = products;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> replenishable(Collection<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return Set.of();
        }
        return products.purchasability(Set.copyOf(skus), false).entrySet().stream()
                .filter(e -> e.getValue().isPurchasable())
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
