package com.stockflow.inventory.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.ItemPurchasing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ItemPurchasingRulesTest {

    @Test
    void everythingIsOptional() {
        var empty = new ItemPurchasing(null, null, null, null, null);
        assertThat(ItemPurchasingRules.validate(empty)).isSameAs(empty);
        var full = new ItemPurchasing(UUID.randomUUID(), new BigDecimal("1250.50"), "VND", 365, 1);
        assertThat(ItemPurchasingRules.validate(full)).isSameAs(full);
        assertThat(ItemPurchasingRules.validate(new ItemPurchasing(null, null, null, 0, null))).isNotNull();
    }

    @Test
    void priceAndCurrencyGoTogether() {
        refused(new ItemPurchasing(null, new BigDecimal("10"), null, null, null), "referencePurchaseCurrency");
        refused(new ItemPurchasing(null, null, "VND", null, null), "referencePurchasePrice");
        refused(new ItemPurchasing(null, new BigDecimal("10"), "vnd", null, null), "referencePurchaseCurrency");
    }

    @Test
    void priceIsPositiveWithAtMostTwoDecimals() {
        refused(new ItemPurchasing(null, BigDecimal.ZERO, "VND", null, null), "referencePurchasePrice");
        refused(new ItemPurchasing(null, new BigDecimal("-1"), "VND", null, null), "referencePurchasePrice");
        refused(new ItemPurchasing(null, new BigDecimal("1.005"), "VND", null, null), "referencePurchasePrice");
        assertThat(ItemPurchasingRules.validate(new ItemPurchasing(null, new BigDecimal("1.500"), "USD", null, null)))
                .isNotNull();
    }

    @Test
    void leadTimeAndMoqLimits() {
        refused(new ItemPurchasing(null, null, null, -1, null), "leadTimeDays");
        refused(new ItemPurchasing(null, null, null, 366, null), "leadTimeDays");
        refused(new ItemPurchasing(null, null, null, null, 0), "moq");
    }

    private static void refused(ItemPurchasing p, String field) {
        assertThatThrownBy(() -> ItemPurchasingRules.validate(p)).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(e.fieldErrors()).extracting(FieldError::field).containsExactly(field);
        });
    }
}
