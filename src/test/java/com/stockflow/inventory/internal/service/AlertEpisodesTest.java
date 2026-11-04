package com.stockflow.inventory.internal.service;

import com.stockflow.contracts.InventoryAlertsRaised;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.domain.StockAlert.Key;
import com.stockflow.inventory.internal.domain.StockAlert.Kind;
import com.stockflow.inventory.internal.repository.StockAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Opening, refreshing and closing episodes; the 6-hour re-touch window; what is announced and what is not. */
class AlertEpisodesTest {

    private static final Instant NOW = Instant.parse("2026-10-14T08:00:00Z");
    private static final Key KEY = Key.stockLevel(Kind.REORDER, "SKU-1");

    private StockAlertRepository alerts;
    private ApplicationEventPublisher events;
    private AlertEpisodes episodes;

    @BeforeEach
    void setUp() {
        alerts = mock(StockAlertRepository.class);
        events = mock(ApplicationEventPublisher.class);
        episodes = new AlertEpisodes(alerts, events, Duration.ofHours(6));
        when(alerts.insertIfAbsent(any())).thenReturn(true);
        when(alerts.lastResolvedAt(any())).thenReturn(Optional.empty());
    }

    private static StockAlert open(int qty) {
        return StockAlert.open(UUID.randomUUID(), KEY, qty, 10, false, NOW.minusSeconds(3600));
    }

    @Test
    void aNewConditionOpensAnEpisodeAndIsNews() {
        Optional<StockAlert> raised = episodes.raise(null, KEY, 3, 10, false, NOW);
        assertThat(raised).isPresent();
        assertThat(raised.get().observedQty()).isEqualTo(3);
        verify(alerts).insertIfAbsent(any());
    }

    @Test
    void aConditionThatIsAlreadyOpenOnlyRefreshesItsNumbersAndIsNotNews() {
        StockAlert existing = open(5);
        assertThat(episodes.raise(existing, KEY, 2, 10, false, NOW)).isEmpty();
        assertThat(existing.observedQty()).isEqualTo(2);
        verify(alerts).update(existing);
        verify(alerts, never()).insertIfAbsent(any());
    }

    @Test
    void anUnchangedOpenAlertIsNotWrittenAgain() {
        StockAlert existing = open(5);
        assertThat(episodes.raise(existing, KEY, 5, 10, false, NOW)).isEmpty();
        verify(alerts, never()).update(any());
    }

    @Test
    void aChangedThresholdOfAnOpenAlertIsRefreshed() {
        StockAlert existing = open(5);
        episodes.raise(existing, KEY, 5, 12, false, NOW);
        assertThat(existing.thresholdQty()).isEqualTo(12);
        verify(alerts).update(existing);
    }

    @Test
    void anEpisodeThatEndedLessThanSixHoursAgoReopensQuietly() {
        when(alerts.lastResolvedAt(KEY)).thenReturn(Optional.of(NOW.minus(Duration.ofHours(6)).plusSeconds(60)));
        // resolved 5h59m ago: inside the window
        assertThat(episodes.raise(null, KEY, 3, 10, false, NOW)).isEmpty();
        // but the row is there: the list shows it
        verify(alerts).insertIfAbsent(any());
    }

    @Test
    void anEpisodeThatEndedExactlySixHoursAgoIsNewsAgain() {
        when(alerts.lastResolvedAt(KEY)).thenReturn(Optional.of(NOW.minus(Duration.ofHours(6))));
        assertThat(episodes.raise(null, KEY, 3, 10, false, NOW)).isPresent();
    }

    @Test
    void anEpisodeThatEndedMoreThanSixHoursAgoIsNewsAgain() {
        when(alerts.lastResolvedAt(KEY)).thenReturn(Optional.of(NOW.minus(Duration.ofHours(6)).minusSeconds(1)));
        assertThat(episodes.raise(null, KEY, 3, 10, false, NOW)).isPresent();
    }

    @Test
    void aWindowOfZeroNeverSilencesAReopening() {
        AlertEpisodes noWindow = new AlertEpisodes(alerts, events, Duration.ZERO);
        when(alerts.lastResolvedAt(KEY)).thenReturn(Optional.of(NOW.minusSeconds(1)));
        assertThat(noWindow.raise(null, KEY, 3, 10, false, NOW)).isPresent();
    }

    @Test
    void whenAnotherWriterOpenedTheConditionFirstNothingIsAnnounced() {
        when(alerts.insertIfAbsent(any())).thenReturn(false);
        assertThat(episodes.raise(null, KEY, 3, 10, false, NOW)).isEmpty();
    }

    @Test
    void aNegativeWindowIsAConfigurationError() {
        assertThatThrownBy(() -> new AlertEpisodes(alerts, events, Duration.ofHours(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settlingClosesTheEpisodeOnceAndWritesIt() {
        StockAlert existing = open(5);
        episodes.settle(existing, StockAlert.Resolution.RECOVERED, NOW);
        assertThat(existing.status()).isEqualTo(StockAlert.Status.RESOLVED);
        verify(alerts).update(existing);
        episodes.settle(existing, StockAlert.Resolution.RECOVERED, NOW);
        verify(alerts).update(existing); // still once
    }

    @Test
    void announcingGroupsByKindAndWarehouseAndSaysNothingForNothing() {
        UUID hcm = UUID.randomUUID();
        UUID hn = UUID.randomUUID();
        LocalDate expiry = LocalDate.of(2026, 10, 20);
        StockAlert reorderA = StockAlert.open(UUID.randomUUID(), Key.stockLevel(Kind.REORDER, "A"), 1, 5, false, NOW);
        StockAlert reorderB = StockAlert.open(UUID.randomUUID(), Key.stockLevel(Kind.REORDER, "B"), 1, 5, false, NOW);
        StockAlert safety = StockAlert.open(UUID.randomUUID(), Key.stockLevel(Kind.SAFETY, "A"), 1, 5, false, NOW);
        StockAlert expHcm = StockAlert.open(UUID.randomUUID(), Key.expiry(Kind.EXPIRING, "A", hcm, "L1", expiry), 4, null, false, NOW);
        StockAlert expHn = StockAlert.open(UUID.randomUUID(), Key.expiry(Kind.EXPIRING, "A", hn, "L1", expiry), 4, null, false, NOW);

        episodes.announce(List.of(), NOW);
        verify(events, never()).publishEvent(any(Object.class));

        episodes.announce(List.of(reorderA, safety, reorderB, expHcm, expHn), NOW);
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.times(4)).publishEvent(published.capture());
        List<InventoryAlertsRaised> raised = published.getAllValues().stream().map(InventoryAlertsRaised.class::cast).toList();
        assertThat(raised).extracting(InventoryAlertsRaised::kind).containsExactly("REORDER", "SAFETY", "EXPIRING", "EXPIRING");
        assertThat(raised.get(0).items()).extracting(InventoryAlertsRaised.Item::sku).containsExactly("A", "B");
        assertThat(raised.get(0).warehouseId()).isNull();
        assertThat(raised.get(2).warehouseId()).isEqualTo(hcm);
        assertThat(raised.get(3).warehouseId()).isEqualTo(hn);
        assertThat(raised.get(2).items().get(0).lotNumber()).isEqualTo("L1");
        assertThat(raised.get(2).items().get(0).expiryDate()).isEqualTo(expiry);
        assertThat(raised).allSatisfy(e -> assertThat(e.batchId()).isNotNull());
    }
}
