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

/** kltn-docs 13 BR-04 (2026-11-26): a variant's standard price, by carton break. */
class StandardPriceTest {

    private static StandardPrice.Tier tier(int from, int price) {
        return new StandardPrice.Tier(from, BigDecimal.valueOf(price));
    }

    @Test
    void breaksAreKeptInOrderAndReadAtACartonCount() {
        StandardPrice price = new StandardPrice(UUID.randomUUID(), "LY-12OZ", List.of(tier(50, 1150), tier(10, 1300)),
                "  thuong  ", 0);

        assertThat(price.tiers()).extracting(StandardPrice.Tier::minQuantity).containsExactly(10, 50);
        assertThat(price.moq()).isEqualTo(10);
        assertThat(price.priceFor(9)).isEmpty();
        assertThat(price.priceFor(49)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("1300"));
        assertThat(price.priceFor(50)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("1150"));
        assertThat(price.note()).isEqualTo("thuong");
    }

    @Test
    void theBreaksFollowTheCataloguesRules() {
        assertThatThrownBy(() -> new StandardPrice(UUID.randomUUID(), "LY", List.of(), null, 0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new StandardPrice(UUID.randomUUID(), "LY", List.of(tier(10, 1), tier(10, 2)), null, 0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tier(0, 1000)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new StandardPrice.Tier(1, new BigDecimal("10.5"))).isInstanceOf(BusinessException.class);
        List<StandardPrice.Tier> many = new ArrayList<>();
        for (int i = 1; i <= PriceTiers.MAX + 1; i++) many.add(tier(i, 100));
        assertThatThrownBy(() -> new StandardPrice(UUID.randomUUID(), "LY", many, null, 0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aStaleEditorIsRefused() {
        StandardPrice price = new StandardPrice(UUID.randomUUID(), "LY", List.of(tier(1, 1000)), null, 3);

        assertThatThrownBy(() -> price.replace(List.of(tier(1, 900)), null, 2))
                .satisfies(e -> assertThat(((BusinessException) e).errorCode()).isEqualTo(ErrorCode.OPTIMISTIC_LOCK));
        price.replace(List.of(tier(1, 900)), null, 3);
        assertThat(price.priceFor(1)).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("900"));
    }
}
