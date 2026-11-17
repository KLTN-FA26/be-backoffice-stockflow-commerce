package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.api.CatalogService;
import com.stockflow.catalog.api.PriceSchedule;
import com.stockflow.product.api.CustomerSkus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The catalog's prices for the other modules, read-only: the customer's own price a quote line starts from, and the
 * tiers the back office shows. Nothing here is priced for checkout or locked for one - the customer does not place
 * orders (kltn-docs 14 BR-01, BR-02; team plan 5-2); a quote takes its own locks
 * ({@code ProductService.lockedPurchasability}).
 *
 * <p>A schedule says what its tiers count ({@link PriceSchedule.Measure}) by the rule the customer-prices screen uses
 * ({@code CustomerPriceService}): cartons when the SKU is this customer's own, units otherwise. The quote never needed
 * it - it accepts only the customer's own SKUs - but a reader of the bare numbers, the assistant, took "10.000 ly" for
 * 10,000 cartons.</p>
 */
@Service
@Transactional(readOnly = true)
class CatalogServiceImpl implements CatalogService {

    private final PriceResolver resolver;
    private final CustomerSkus customerSkus;

    CatalogServiceImpl(PriceResolver resolver, CustomerSkus customerSkus) {
        this.resolver = resolver;
        this.customerSkus = customerSkus;
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<PriceSchedule> customerPrice(UUID customerId, String sku) {
        return resolver.own(resolver.customer(customerId), sku).map(schedule -> schedule.measuredIn(
                customerSkus.ownerOf(schedule.sku()).map(customerId::equals).orElse(false)
                        ? PriceSchedule.Measure.CARTON : PriceSchedule.Measure.UNIT));
    }
}
