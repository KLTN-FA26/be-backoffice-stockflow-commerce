package com.stockflow.catalog.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rules of a customer's own price; the same rules are CHECKs on catalog.customer_price_tier. */
class CustomerPriceTest {

    private static CustomerPrice.Tier tier(int min, long price) {
        return new CustomerPrice.Tier(min, BigDecimal.valueOf(price));
    }

    private static CustomerPrice price(List<CustomerPrice.Tier> tiers) {
        return new CustomerPrice(UUID.randomUUID(), UUID.randomUUID(), "SOFA-3S-GREY", tiers, null, 0);
    }

    @Test
    void tiersAreSortedTheFirstIsTheMoqAndTheLastReachedTierApplies() {
        CustomerPrice price = price(List.of(tier(100, 9_000), tier(10, 10_000), tier(1_000, 8_500)));
        assertThat(price.tiers()).extracting(CustomerPrice.Tier::minQuantity).containsExactly(10, 100, 1_000);
        assertThat(price.moq()).isEqualTo(10);
        assertThat(price.priceFor(9)).isEmpty();
        assertThat(price.priceFor(10)).contains(new BigDecimal("10000"));
        assertThat(price.priceFor(99)).contains(new BigDecimal("10000"));
        assertThat(price.priceFor(100)).contains(new BigDecimal("9000"));
        assertThat(price.priceFor(5_000)).contains(new BigDecimal("8500"));
    }

    @Test
    void noOrderBetweenTierPricesIsImposed() {
        // The documents give none; a larger quantity may cost more per unit if the admin says so.
        CustomerPrice price = price(List.of(tier(1, 1_000), tier(5, 1_200)));
        assertThat(price.priceFor(5)).contains(new BigDecimal("1200"));
    }

    @Test
    void invalidTiersAreRefused() {
        for (List<CustomerPrice.Tier> tiers : List.<List<CustomerPrice.Tier>>of(List.of(),
                List.of(tier(5, 100), tier(5, 90)))) {
            assertThatThrownBy(() -> price(tiers)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
        assertThatThrownBy(() -> price(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tier(0, 100)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tier(1, 0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new CustomerPrice.Tier(1, new BigDecimal("10.5"))).isInstanceOf(BusinessException.class);
        List<CustomerPrice.Tier> many = new ArrayList<>();
        for (int i = 1; i <= CustomerPrice.MAX_TIERS + 1; i++) many.add(tier(i, 100));
        assertThatThrownBy(() -> price(many)).isInstanceOf(BusinessException.class);
    }

    @Test
    void aStaleEditorCannotReplaceTheTiers() {
        CustomerPrice price = new CustomerPrice(UUID.randomUUID(), UUID.randomUUID(), "SKU-1", List.of(tier(1, 100)),
                null, 3);
        assertThatThrownBy(() -> price.replace(List.of(tier(1, 90)), null, 2))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK));
        price.replace(List.of(tier(2, 90)), " note ", 3);
        assertThat(price.moq()).isEqualTo(2);
        assertThat(price.note()).isEqualTo("note");
    }
}
