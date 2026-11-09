package com.stockflow.catalog.internal.service;

import com.stockflow.catalog.api.PriceSchedule;
import com.stockflow.catalog.internal.domain.CustomerPrice;
import com.stockflow.catalog.internal.domain.CustomerPriceRepository;
import com.stockflow.catalog.internal.repository.ListingRepository;
import com.stockflow.common.domain.Money;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.product.api.ProductPublication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 4B-2 and 5-3 (kltn-docs 13 BR-04, BR-05, BR-10; 14 BR-01, BR-08): a price of the customer's own on one of its own SKUs
 * counts cartons and is internal; it is the only price there is - no base, no group price. Checkout refuses the
 * customer's own SKU outright, never reads a carton price as a unit price, and finds no price at all for a stock variant
 * the customer has none of its own on.
 */
class CustomerSkuPricesTest {

    private static final String OWN = "LY12-ACME-01";
    private static final String STOCK = "SOFA-3S-GREY";
    private static final String UNPRICED = "TABLE-OAK-160";

    private final CustomerPriceRepository prices = mock(CustomerPriceRepository.class);
    private final ListingRepository listings = mock(ListingRepository.class);
    private final CustomerService customers = mock(CustomerService.class);
    private final CustomerSkus customerSkus = mock(CustomerSkus.class);
    private final PriceResolver resolver = new PriceResolver(prices, customers, customerSkus);
    private final UUID customer = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final CustomerSummary summary = new CustomerSummary(customer, user, "A", "a@x.vn", null, null, null,
            "ACTIVE", 0, Instant.now());

    @BeforeEach
    void stubs() {
        when(customers.findById(customer)).thenReturn(Optional.of(summary));
        when(customerSkus.ownerOf(OWN)).thenReturn(Optional.of(customer));
        when(customerSkus.ownerOf(STOCK)).thenReturn(Optional.empty());
        when(customerSkus.ownerOf(UNPRICED)).thenReturn(Optional.empty());
        when(customerSkus.ownersOf(anyCollection())).thenAnswer(call -> ((java.util.Collection<?>) call.getArgument(0))
                .contains(OWN) ? Map.of(OWN, customer) : Map.of());
        when(prices.find(customer, OWN)).thenReturn(Optional.of(new CustomerPrice(UUID.randomUUID(), customer, OWN,
                List.of(new CustomerPrice.Tier(10, new BigDecimal("1800"))), null, 0)));
        when(prices.find(customer, STOCK)).thenReturn(Optional.of(new CustomerPrice(UUID.randomUUID(), customer, STOCK,
                List.of(new CustomerPrice.Tier(1, new BigDecimal("11000000")),
                        new CustomerPrice.Tier(5, new BigDecimal("10500000"))), null, 0)));
        when(prices.find(customer, UNPRICED)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("checkout refuses the customer's own SKU before anything is locked or priced")
    void checkoutRefusesTheCustomersOwnSku() {
        ProductPublication publication = mock(ProductPublication.class);
        CatalogServiceImpl catalog = new CatalogServiceImpl(listings, publication, resolver, customerSkus);

        BusinessException refused = catchThrowableOfType(BusinessException.class,
                () -> catalog.checkoutPrices(customer, Map.of(OWN, 20)));

        assertThat(refused).isNotNull();
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.PRODUCT_NOT_PURCHASABLE);
        assertThat(refused.getMessage()).contains(OWN).contains("quoted");
        verifyNoInteractions(publication);
    }

    @Test
    @DisplayName("checkout never reads a carton price as a unit price: the customer's own SKU has no unit price at all")
    void aCartonPriceIsNeverAUnitPrice() {
        BusinessException none = catchThrowableOfType(BusinessException.class,
                () -> resolver.checkoutSchedule(summary, OWN));

        assertThat(none).isNotNull();
        assertThat(none.errorCode()).isEqualTo(ErrorCode.PRICE_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("a stock variant without a price of the customer's own has none: no base or group price behind it")
    void noBaseOrGroupPriceBehindTheCustomersOwn() {
        BusinessException none = catchThrowableOfType(BusinessException.class,
                () -> resolver.checkoutSchedule(summary, UNPRICED));

        assertThat(none).isNotNull();
        assertThat(none.errorCode()).isEqualTo(ErrorCode.PRICE_NOT_AVAILABLE);
        assertThat(none.getMessage()).contains(UNPRICED);
        assertThat(resolver.own(summary, UNPRICED)).isEmpty();
    }

    @Test
    @DisplayName("a price kept on a stock variant counts units and is read tier by tier; a quote reads the carton price")
    void aKeptUnitPriceIsReadByTierAndAQuoteReadsCartons() {
        PriceSchedule stock = resolver.checkoutSchedule(summary, STOCK);
        assertThat(stock.moq()).isEqualTo(1);
        assertThat(PriceResolver.unitPrice(stock, 4)).isEqualTo(Money.vnd(11_000_000));
        assertThat(PriceResolver.unitPrice(stock, 5)).isEqualTo(Money.vnd(10_500_000));

        assertThat(resolver.own(summary, OWN)).hasValueSatisfying(schedule -> {
            assertThat(schedule.moq()).as("10 cartons").isEqualTo(10);
            assertThat(schedule.tiers().getFirst().unitPrice().amount()).isEqualByComparingTo("1800");
        });
        BusinessException below = catchThrowableOfType(BusinessException.class,
                () -> PriceResolver.unitPrice(resolver.own(summary, OWN).orElseThrow(), 9));
        assertThat(below.errorCode()).isEqualTo(ErrorCode.CUSTOMER_PRICE_BELOW_MOQ);
    }
}
