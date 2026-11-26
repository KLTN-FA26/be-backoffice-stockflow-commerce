package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.api.PriceSchedule;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The catalog's prices for the other modules, read-only: the customer's own price and the variant's standard price a
 * quote line starts from (13 BR-04, 2026-11-26), and the tiers the back office shows. Nothing here is priced for checkout or locked for one - the customer does not place
 * orders (kltn-docs 14 BR-01, BR-02; team plan 5-2); a quote takes its own locks
 * ({@code ProductService.lockedPurchasability}).
 */
@Service
@Transactional(readOnly = true)
class CatalogServiceImpl implements CatalogService {

    private final PriceResolver resolver;
    private final com.stockflow.catalog.internal.domain.StandardPriceRepository standardPrices;

    CatalogServiceImpl(PriceResolver resolver,
                       com.stockflow.catalog.internal.domain.StandardPriceRepository standardPrices) {
        this.resolver = resolver;
        this.standardPrices = standardPrices;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<PriceSchedule> customerPrice(UUID customerId, String sku) {
        return resolver.own(resolver.customer(customerId), sku);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<PriceSchedule> standardPrice(String sku) {
        if (sku == null || sku.isBlank()) {
            return java.util.Optional.empty();
        }
        return standardPrices.find(sku.strip().toUpperCase(java.util.Locale.ROOT)).map(price -> new PriceSchedule(
                price.sku(), price.moq(), price.tiers().stream().map(tier -> new PriceSchedule.Tier(tier.minQuantity(),
                        new com.stockflow.common.domain.Money(tier.unitPrice(),
                                com.stockflow.common.domain.Money.VND))).toList()));
    }
}
