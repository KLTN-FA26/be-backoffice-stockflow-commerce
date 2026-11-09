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
 * Joins the caller's transaction; locking is the caller's ({@link CatalogServiceImpl} locks the products first).
 *
 * <p>Since 4B-2 a customer's own price for one of its own SKUs counts cartons (13 BR-05): what a quote starts from
 * ({@link #own}). Checkout counts units, so it reads a customer's own price only on a stock variant
 * ({@link #checkoutSchedule}) - a row set before 4B-2 and kept until checkout goes (team plan 5-2); the customer's own
 * SKU is quoted, not bought at checkout.</p>
 */
@Component
class PriceResolver {

    private final CustomerPriceRepository prices;
    private final CustomerService customers;
    private final com.stockflow.product.api.CustomerSkus customerSkus;

    PriceResolver(CustomerPriceRepository prices, CustomerService customers,
                  com.stockflow.product.api.CustomerSkus customerSkus) {
        this.customerSkus = customerSkus;
        this.prices = prices;
        this.customers = customers;
    }

    /**
     * The customer's own price on a stock variant, counted in units, for checkout. A customer's own price on one of its
     * own SKUs counts cartons and is never read here: the caller refuses such a SKU first (it is quoted - 14 BR-01,
     * BR-08); this keeps a carton price from being read as a unit price should one reach here anyway.
     *
     * @throws BusinessException {@code PRICE_NOT_AVAILABLE} without one
     */
    PriceSchedule checkoutSchedule(CustomerSummary customer, String sku) {
        return prices.find(customer.customerId(), sku)
                .filter(price -> customerSkus.ownerOf(sku).isEmpty())
                .map(price -> schedule(sku, price))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRICE_NOT_AVAILABLE, ("%s has no price of this "
                        + "customer's own: there is no base or group price (kltn-docs 13 BR-04)").formatted(sku)));
    }

    /** The customer's own price for {@code sku}, if it has one. */
    Optional<PriceSchedule> own(CustomerSummary customer, String sku) {
        return prices.find(customer.customerId(), sku).map(price -> schedule(sku, price));
    }

    /**
     * The unit price at this quantity: the last tier that starts at or below it.
     *
     * @throws BusinessException {@code CUSTOMER_PRICE_BELOW_MOQ} below the first tier (BR-05)
     */
    static Money unitPrice(PriceSchedule schedule, int quantity) {
        Money price = null;
        for (PriceSchedule.Tier tier : schedule.tiers()) {
            if (tier.minQuantity() > quantity) break;
            price = tier.unitPrice();
        }
        if (price == null) {
            throw new BusinessException(ErrorCode.CUSTOMER_PRICE_BELOW_MOQ,
                    "%d units of %s are below this customer's minimum of %d"
                            .formatted(quantity, schedule.sku(), schedule.moq()));
        }
        return price;
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
