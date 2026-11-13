package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.api.PriceSchedule;
import com.stockflow.catalog.internal.domain.CustomerPrice;
import com.stockflow.catalog.internal.domain.CustomerPriceRepository;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Which price a customer pays for a SKU at a quantity: its own tier, the one source of a price (kltn-docs 13 BR-04,
 * D14). There is no base or group price behind it since 5-3: a SKU without the customer's own price has no price.
 * Joins the caller's transaction and reads only: nothing is priced for a checkout any more (team plan 5-2).
 *
 * <p>Since 4B-2 a customer's own price for one of its own SKUs counts cartons (13 BR-05): what a quote starts from
 * ({@link #own}). Checkout read one in units on a stock variant until it went (team plan 5-2), so that reading is gone
 * too.</p>
 */
@Component
class PriceResolver {

    private final CustomerPriceRepository prices;
    private final CustomerService customers;

    PriceResolver(CustomerPriceRepository prices, CustomerService customers) {
        this.prices = prices;
        this.customers = customers;
    }

    /** The customer's own price for {@code sku}, if it has one. */
    Optional<PriceSchedule> own(CustomerSummary customer, String sku) {
        return prices.find(customer.customerId(), sku).map(price -> schedule(sku, price));
    }

    CustomerSummary customer(UUID customerId) {
        return customers.findById(customerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
    }

    private static PriceSchedule schedule(String sku, CustomerPrice price) {
        return new PriceSchedule(sku, price.moq(), price.tiers().stream()
                .map(tier -> new PriceSchedule.Tier(tier.minQuantity(), new Money(tier.unitPrice(), Money.VND)))
                .toList());
    }
}
