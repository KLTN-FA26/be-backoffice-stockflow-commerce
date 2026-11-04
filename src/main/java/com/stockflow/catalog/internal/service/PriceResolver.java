package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.api.PriceSchedule;
import com.stockflow.catalog.internal.domain.CustomerPrice;
import com.stockflow.catalog.internal.domain.CustomerPriceRepository;
import com.stockflow.catalog.internal.domain.SellingPrice;
import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Which price a customer pays for a SKU at a quantity (kltn-docs 13 §3): the customer's own tier, or,
 * with no own price, its group's price, or the base price - the last two from one unit on. Joins the
 * caller's transaction; locking is the caller's ({@link CatalogServiceImpl} locks the products first).
 */
@Component
class PriceResolver {

    private final CustomerPriceRepository prices;
    private final ListingRepository listings;
    private final CustomerService customers;

    PriceResolver(CustomerPriceRepository prices, ListingRepository listings, CustomerService customers) {
        this.prices = prices;
        this.listings = listings;
        this.customers = customers;
    }

    /** @throws BusinessException {@code CUSTOMER_NOT_FOUND}, {@code PRICE_NOT_AVAILABLE} */
    PriceSchedule schedule(UUID customerId, String sku) {
        return schedule(customer(customerId), sku);
    }

    PriceSchedule schedule(CustomerSummary customer, String sku) {
        Optional<CustomerPrice> own = prices.find(customer.customerId(), sku);
        if (own.isPresent()) {
            return new PriceSchedule(sku, PriceSchedule.PriceSource.CUSTOMER, own.get().moq(),
                    own.get().tiers().stream()
                            .map(tier -> new PriceSchedule.Tier(tier.minQuantity(), vnd(tier.unitPrice())))
                            .toList());
        }
        Optional<SellingPrice> segment = customer.segmentId() == null
                ? Optional.empty() : listings.segmentPrice(customer.segmentId(), sku);
        if (segment.isPresent()) return fromOneUnit(sku, PriceSchedule.PriceSource.SEGMENT, segment.get());
        return listings.price(sku)
                .map(base -> fromOneUnit(sku, PriceSchedule.PriceSource.BASE, base))
                .orElseThrow(() -> new BusinessException(ErrorCode.PRICE_NOT_AVAILABLE));
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

    private static PriceSchedule fromOneUnit(String sku, PriceSchedule.PriceSource source, SellingPrice price) {
        return new PriceSchedule(sku, source, 1, List.of(new PriceSchedule.Tier(1, vnd(price.amount()))));
    }

    private static Money vnd(java.math.BigDecimal amount) {
        return new Money(amount, Money.VND);
    }
}
