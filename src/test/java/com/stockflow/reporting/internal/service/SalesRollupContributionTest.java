package com.stockflow.reporting.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What an order contributes: DRAFT never, ON_HOLD yes (DASHHOLD), the placement day in Asia/Ho_Chi_Minh, VND only. */
class SalesRollupContributionTest {

    private static OrderSummary order(OrderStatus status, Money total, Instant placedAt) {
        return new OrderSummary(UUID.randomUUID(), "SO-1", UUID.randomUUID(), status, total, List.of(
                new OrderSummary.LineSummary(UUID.randomUUID(), "CUP", 2, Money.vnd(6_000_000), Money.vnd(12_000_000), List.of()),
                new OrderSummary.LineSummary(UUID.randomUUID(), "LID", 5, Money.vnd(100_000), Money.vnd(500_000), List.of())),
                placedAt);
    }

    @Test
    @DisplayName("an order contributes its total and, per line, SKU, units and line total, on its Ho Chi Minh placement day")
    void contributes() {
        var contribution = SalesRollupService.contributionOf(
                order(OrderStatus.CONFIRMED, Money.vnd(12_500_000), Instant.parse("2026-10-24T17:10:00Z"))).orElseThrow();

        assertThat(contribution.day()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(contribution.total()).isEqualByComparingTo("12500000");
        assertThat(contribution.currency()).isEqualTo("VND");
        assertThat(contribution.lines()).extracting("sku", "quantity").containsExactly(
                org.assertj.core.groups.Tuple.tuple("CUP", 2), org.assertj.core.groups.Tuple.tuple("LID", 5));
        assertThat(contribution.lines().get(0).revenue()).isEqualByComparingTo("12000000");
    }

    @Test
    @DisplayName("an ON_HOLD order counts (DASHHOLD)")
    void onHoldCounts() {
        assertThat(SalesRollupService.contributionOf(
                order(OrderStatus.ON_HOLD, Money.vnd(1_000), Instant.parse("2026-10-24T05:00:00Z")))).isPresent();
    }

    @Test
    @DisplayName("a DRAFT order is not a sale")
    void draftDoesNotCount() {
        assertThat(SalesRollupService.contributionOf(
                order(OrderStatus.DRAFT, Money.vnd(1_000), Instant.parse("2026-10-24T05:00:00Z")))).isEmpty();
    }

    @Test
    @DisplayName("an order in another currency is left out of the single-currency VND totals")
    void otherCurrencyIsLeftOut() {
        assertThat(SalesRollupService.contributionOf(order(OrderStatus.CONFIRMED,
                new Money(java.math.BigDecimal.TEN, Currency.getInstance("USD")), Instant.parse("2026-10-24T05:00:00Z"))))
                .isEmpty();
    }
}
