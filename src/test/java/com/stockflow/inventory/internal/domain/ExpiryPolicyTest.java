package com.stockflow.inventory.internal.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The boundary days of the expiry sweep: yesterday, today, today+30, today+31, and a lot held by a reservation. */
class ExpiryPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    @Test
    void yesterdayIsExpiredAndTodayIsStillSellable() {
        assertThat(ExpiryPolicy.isExpired(TODAY.minusDays(1), TODAY)).isTrue();
        assertThat(ExpiryPolicy.isExpired(TODAY, TODAY)).isFalse();
        assertThat(ExpiryPolicy.isExpired(null, TODAY)).isFalse();
    }

    @Test
    void theWarningWindowRunsFromTodayThroughTodayPlusN() {
        assertThat(ExpiryPolicy.isNearExpiry(TODAY.minusDays(1), TODAY, 30)).isFalse();
        assertThat(ExpiryPolicy.isNearExpiry(TODAY, TODAY, 30)).isTrue();
        assertThat(ExpiryPolicy.isNearExpiry(TODAY.plusDays(30), TODAY, 30)).isTrue();
        assertThat(ExpiryPolicy.isNearExpiry(TODAY.plusDays(31), TODAY, 30)).isFalse();
        assertThat(ExpiryPolicy.isNearExpiry(null, TODAY, 30)).isFalse();
    }

    @Test
    void theWindowFollowsTheConfiguredNumberOfDays() {
        assertThat(ExpiryPolicy.isNearExpiry(TODAY.plusDays(7), TODAY, 7)).isTrue();
        assertThat(ExpiryPolicy.isNearExpiry(TODAY.plusDays(8), TODAY, 7)).isFalse();
    }

    @Test
    void theKindOfAlertAlotCallsForIsChosenByItsDate() {
        assertThat(ExpiryPolicy.alertKind(TODAY.minusDays(1), TODAY, 30)).contains(StockAlert.Kind.EXPIRED);
        assertThat(ExpiryPolicy.alertKind(TODAY, TODAY, 30)).contains(StockAlert.Kind.EXPIRING);
        assertThat(ExpiryPolicy.alertKind(TODAY.plusDays(30), TODAY, 30)).contains(StockAlert.Kind.EXPIRING);
        assertThat(ExpiryPolicy.alertKind(TODAY.plusDays(31), TODAY, 30)).isEmpty();
        assertThat(ExpiryPolicy.alertKind(null, TODAY, 30)).isEmpty();
    }

    @Test
    void aLotHeldByAReservationIsNotBlocked() {
        assertThat(ExpiryPolicy.canBlock(0)).isTrue();
        assertThat(ExpiryPolicy.canBlock(1)).isFalse();
    }
}
