package com.stockflow.procurement.internal.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;

class PurchasePricesTest {

    private static final Currency VND = Currency.getInstance("VND");
    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency KWD = Currency.getInstance("KWD");

    private static boolean ok(String amount, Currency currency) {
        return PurchasePrices.acceptable(new BigDecimal(amount), currency);
    }

    @Test
    @DisplayName("a VND price is a positive whole amount within the column")
    void vnd() {
        assertThat(ok("1500", VND)).isTrue();
        assertThat(ok("1500.00", VND)).as("trailing zeros are not decimals").isTrue();
        assertThat(ok("1500.50", VND)).isFalse();
        assertThat(ok("0", VND)).isFalse();
        assertThat(ok("-1", VND)).isFalse();
        assertThat(PurchasePrices.acceptable(null, VND)).isFalse();
        assertThat(ok("10000000000000000", VND)).isFalse();
        assertThat(ok("9999999999999999", VND)).isTrue();
    }

    @Test
    @DisplayName("USD takes two decimals")
    void usd() {
        assertThat(ok("1.23", USD)).isTrue();
        assertThat(ok("1.234", USD)).isFalse();
    }

    @Test
    @DisplayName("a three-digit currency is still capped at the two-decimal column")
    void kwd() {
        assertThat(ok("1.23", KWD)).isTrue();
        assertThat(ok("1.234", KWD)).isFalse();
    }

    @Test
    @DisplayName("the ceiling is the numeric(18,2) maximum")
    void ceiling() {
        assertThat(PurchasePrices.MAX).isEqualByComparingTo("9999999999999999.99");
        assertThat(ok("9999999999999999.99", USD)).isTrue();
        assertThat(ok("10000000000000000.00", USD)).isFalse();
    }

    @Test
    @DisplayName("a currency without fraction digits is refused")
    void pseudoCurrency() {
        assertThat(ok("1", Currency.getInstance("XXX"))).isFalse();
    }
}
