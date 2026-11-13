package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.api.PriceSchedule;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The catalog's prices for the other modules, read-only: the customer's own price a quote line starts from, and the
 * tiers the back office shows. Nothing here is priced for checkout or locked for one - the customer does not place
 * orders (kltn-docs 14 BR-01, BR-02; team plan 5-2); a quote takes its own locks
 * ({@code ProductService.lockedPurchasability}).
 */
@Service
@Transactional(readOnly = true)
class CatalogServiceImpl implements CatalogService {

    private final PriceResolver resolver;

    CatalogServiceImpl(PriceResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<PriceSchedule> customerPrice(UUID customerId, String sku) {
        return resolver.own(resolver.customer(customerId), sku);
    }
}
