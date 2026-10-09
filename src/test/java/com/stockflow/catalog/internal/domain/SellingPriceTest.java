package com.stockflow.catalog.internal.domain;

import com.stockflow.common.error.BusinessException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SellingPriceTest {
    @Test void wholeVndIsNormalizedWithoutChangingValue() {
        assertThat(new SellingPrice(new BigDecimal("2500000.00"), "VND").amount())
                .isEqualTo(new BigDecimal("2500000"));
    }
    @Test void foreignCurrencyAndFractionalDongAreRejectedRatherThanRounded() {
        assertThatThrownBy(() -> new SellingPrice(new BigDecimal("100"), "USD")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new SellingPrice(new BigDecimal("100.49"), "VND")).isInstanceOf(BusinessException.class);
    }
    @Test void nonPositiveAndOversizedPricesAreRejected() {
        for (String value : new String[]{"0", "-1", "10000000000000000"})
            assertThatThrownBy(() -> new SellingPrice(new BigDecimal(value), "VND")).isInstanceOf(BusinessException.class);
    }
}
