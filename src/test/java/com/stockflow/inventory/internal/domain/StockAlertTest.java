package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.StockAlert.Key;
import com.stockflow.inventory.internal.domain.StockAlert.Kind;
import com.stockflow.inventory.internal.domain.StockAlert.Resolution;
import com.stockflow.inventory.internal.domain.StockAlert.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The episode of a stock condition: open, observe, acknowledge, resolve, and every invariant the table also checks. */
class StockAlertTest {

    private static final Instant T0 = Instant.parse("2026-10-14T01:00:00Z");
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private static StockAlert reorder() {
        return StockAlert.open(UUID.randomUUID(), Key.stockLevel(Kind.REORDER, "SKU-1"), 3, 10, false, T0);
    }

    private static StockAlert expired(boolean impaired) {
        return StockAlert.open(UUID.randomUUID(),
                Key.expiry(Kind.EXPIRED, "SKU-1", WAREHOUSE, "LOT-1", LocalDate.of(2026, 10, 13)), 4, null, impaired, T0);
    }

    @Test
    void anOpenedEpisodeIsOpenWithItsNumbersAndNobodyHasSeenIt() {
        StockAlert alert = reorder();
        assertThat(alert.status()).isEqualTo(Status.OPEN);
        assertThat(alert.observedQty()).isEqualTo(3);
        assertThat(alert.thresholdQty()).isEqualTo(10);
        assertThat(alert.openedAt()).isEqualTo(T0);
        assertThat(alert.acknowledgedBy()).isNull();
        assertThat(alert.isOpenEpisode()).isTrue();
        assertThat(alert.version()).isZero();
    }

    @Test
    void observingRefreshesTheNumbersAndNeverMovesTheTimeBackwards() {
        StockAlert alert = reorder();
        alert.observe(1, 10, false, T0.plusSeconds(60));
        assertThat(alert.observedQty()).isEqualTo(1);
        assertThat(alert.lastObservedAt()).isEqualTo(T0.plusSeconds(60));
        alert.observe(2, 10, false, T0.minusSeconds(60));
        assertThat(alert.lastObservedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(alert.status()).isEqualTo(Status.OPEN);
    }

    @Test
    void aResolvedAlertIsHistoryAndCannotBeObserved() {
        StockAlert alert = reorder();
        alert.resolve(Resolution.RECOVERED, T0.plusSeconds(5));
        assertThatThrownBy(() -> alert.observe(1, 10, false, T0.plusSeconds(10))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acknowledgingKeepsTheEpisodeOpenAndRecordsWhoAndWhen() {
        StockAlert alert = reorder();
        alert.acknowledge(USER, "  ordering on Monday ", T0.plusSeconds(30));
        assertThat(alert.status()).isEqualTo(Status.ACKNOWLEDGED);
        assertThat(alert.acknowledgedBy()).isEqualTo(USER);
        assertThat(alert.acknowledgedAt()).isEqualTo(T0.plusSeconds(30));
        assertThat(alert.ackNote()).isEqualTo("ordering on Monday");
        assertThat(alert.isOpenEpisode()).isTrue();
    }

    @Test
    void aBlankNoteIsNoNote() {
        StockAlert alert = reorder();
        alert.acknowledge(USER, "   ", T0);
        assertThat(alert.ackNote()).isNull();
    }

    @Test
    void acknowledgingTwiceOrAfterResolutionIsAnInvalidTransition() {
        StockAlert twice = reorder();
        twice.acknowledge(USER, null, T0);
        assertThatThrownBy(() -> twice.acknowledge(USER, null, T0))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_ALERT_TRANSITION));

        StockAlert resolved = reorder();
        resolved.resolve(Resolution.RECOVERED, T0);
        assertThatThrownBy(() -> resolved.acknowledge(USER, null, T0))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_ALERT_TRANSITION));
    }

    @Test
    void aNoteOver500CharactersIsRefused() {
        StockAlert alert = reorder();
        assertThatThrownBy(() -> alert.acknowledge(USER, "x".repeat(501), T0))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        alert.acknowledge(USER, "x".repeat(500), T0);
        assertThat(alert.ackNote()).hasSize(500);
    }

    @Test
    void resolvingWorksFromOpenAndFromAcknowledgedAndOnlyOnce() {
        StockAlert open = reorder();
        assertThat(open.resolve(Resolution.RECOVERED, T0.plusSeconds(9))).isTrue();
        assertThat(open.status()).isEqualTo(Status.RESOLVED);
        assertThat(open.resolution()).isEqualTo(Resolution.RECOVERED);
        assertThat(open.resolvedAt()).isEqualTo(T0.plusSeconds(9));
        assertThat(open.isOpenEpisode()).isFalse();
        assertThat(open.resolve(Resolution.UNCONFIGURED, T0.plusSeconds(99))).isFalse();
        assertThat(open.resolution()).isEqualTo(Resolution.RECOVERED);

        StockAlert acknowledged = reorder();
        acknowledged.acknowledge(USER, null, T0);
        assertThat(acknowledged.resolve(Resolution.UNCONFIGURED, T0.plusSeconds(1))).isTrue();
        assertThat(acknowledged.acknowledgedBy()).isEqualTo(USER);
    }

    @Test
    void aResolutionTimeBeforeTheAlertOpenedIsClampedToTheOpeningTime() {
        StockAlert alert = reorder();
        alert.resolve(Resolution.RECOVERED, T0.minusSeconds(100));
        assertThat(alert.resolvedAt()).isEqualTo(T0);
    }

    @Test
    void eachKindCanOnlyEndTheWayItMakesSense() {
        assertThat(StockAlert.allows(Kind.REORDER, Resolution.RECOVERED)).isTrue();
        assertThat(StockAlert.allows(Kind.SAFETY, Resolution.UNCONFIGURED)).isTrue();
        assertThat(StockAlert.allows(Kind.REORDER, Resolution.STOCK_GONE)).isFalse();
        assertThat(StockAlert.allows(Kind.EXPIRING, Resolution.EXPIRED_OUT)).isTrue();
        assertThat(StockAlert.allows(Kind.EXPIRING, Resolution.STOCK_GONE)).isTrue();
        assertThat(StockAlert.allows(Kind.EXPIRING, Resolution.RECOVERED)).isFalse();
        assertThat(StockAlert.allows(Kind.EXPIRED, Resolution.STOCK_GONE)).isTrue();
        assertThat(StockAlert.allows(Kind.EXPIRED, Resolution.EXPIRED_OUT)).isFalse();
        StockAlert alert = reorder();
        assertThatThrownBy(() -> alert.resolve(Resolution.EXPIRED_OUT, T0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aStockLevelAlertIsPerSkuAndNamesItsThreshold() {
        assertThatThrownBy(() -> StockAlert.open(UUID.randomUUID(), Key.stockLevel(Kind.REORDER, "S"), 1, null, false, T0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Key.stockLevel(Kind.EXPIRED, "S")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StockAlert.open(UUID.randomUUID(),
                new Key(Kind.SAFETY, "S", WAREHOUSE, null, null), 1, 5, false, T0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anExpiryAlertNamesItsWarehouseAndDate() {
        assertThatThrownBy(() -> Key.expiry(Kind.EXPIRING, "S", null, "L", LocalDate.now())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Key.expiry(Kind.EXPIRING, "S", WAREHOUSE, "L", null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Key.expiry(Kind.REORDER, "S", WAREHOUSE, "L", LocalDate.now())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankLotNumberIsNoLot() {
        Key key = Key.expiry(Kind.EXPIRING, "S", WAREHOUSE, "  ", LocalDate.of(2026, 11, 1));
        assertThat(key.lotNumber()).isNull();
        assertThat(key).isEqualTo(Key.expiry(Kind.EXPIRING, "S", WAREHOUSE, null, LocalDate.of(2026, 11, 1)));
    }

    @Test
    void onlyAnExpiredLotCanBeImpairedByAReservation() {
        assertThat(expired(true).reservationImpaired()).isTrue();
        assertThatThrownBy(() -> StockAlert.open(UUID.randomUUID(),
                Key.expiry(Kind.EXPIRING, "S", WAREHOUSE, "L", LocalDate.of(2026, 10, 20)), 1, null, true, T0))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StockAlert.open(UUID.randomUUID(), Key.stockLevel(Kind.REORDER, "S"), 1, 5, true, T0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void impairmentClearsWhenTheReservationIsGone() {
        StockAlert alert = expired(true);
        alert.observe(4, null, false, T0.plusSeconds(1));
        assertThat(alert.reservationImpaired()).isFalse();
    }

    @Test
    void quantitiesAreNeverNegative() {
        assertThatThrownBy(() -> StockAlert.open(UUID.randomUUID(), Key.stockLevel(Kind.REORDER, "S"), -1, 5, false, T0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aStoredRowThatBreaksAnInvariantIsRefusedWhenLoaded() {
        Key key = Key.stockLevel(Kind.REORDER, "S");
        // RESOLVED without a resolution
        assertThatThrownBy(() -> new StockAlert(UUID.randomUUID(), key, 1, 5, false, Status.RESOLVED, T0, T0, null, null,
                null, T0, null, 1)).isInstanceOf(IllegalStateException.class);
        // OPEN that says it was resolved
        assertThatThrownBy(() -> new StockAlert(UUID.randomUUID(), key, 1, 5, false, Status.OPEN, T0, T0, null, null,
                null, T0, Resolution.RECOVERED, 1)).isInstanceOf(IllegalStateException.class);
        // ACKNOWLEDGED with nobody
        assertThatThrownBy(() -> new StockAlert(UUID.randomUUID(), key, 1, 5, false, Status.ACKNOWLEDGED, T0, T0, null, null,
                null, null, null, 1)).isInstanceOf(IllegalStateException.class);
        // OPEN that was acknowledged
        assertThatThrownBy(() -> new StockAlert(UUID.randomUUID(), key, 1, 5, false, Status.OPEN, T0, T0, USER, T0,
                null, null, null, 1)).isInstanceOf(IllegalStateException.class);
    }
}
