package com.stockflow.inventory.internal.service;

import com.stockflow.contracts.InventoryAlertsRaised;
import com.stockflow.inventory.api.ReplenishmentEligibility;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.domain.StockAlert.Kind;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository.ThresholdRow;
import com.stockflow.inventory.internal.repository.StockAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Thresholds against usable stock: at or below the reorder point, below the safety stock, both, recovery, NULL. */
class StockAlertEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-14T08:00:00Z");

    private InventoryPolicyRepository policies;
    private StockAlertRepository alerts;
    private ApplicationEventPublisher events;
    private ReplenishmentEligibility eligibility;
    private ObjectProvider<ReplenishmentEligibility> owner;
    private StockAlertEvaluator evaluator;
    private final List<StockAlert> open = new ArrayList<>();

    @BeforeEach
    void setUp() {
        policies = mock(InventoryPolicyRepository.class);
        alerts = mock(StockAlertRepository.class);
        events = mock(ApplicationEventPublisher.class);
        when(alerts.insertIfAbsent(any())).thenReturn(true);
        when(alerts.lastResolvedAt(any())).thenReturn(Optional.empty());
        when(alerts.findOpenStockLevel(anyCollection())).thenAnswer(i -> List.copyOf(open));
        // Every SKU may be bought unless a test says otherwise.
        eligibility = mock(ReplenishmentEligibility.class);
        when(eligibility.replenishable(anyCollection())).thenAnswer(i -> Set.copyOf(i.<Collection<String>>getArgument(0)));
        owner = provider(eligibility);
        evaluator = new StockAlertEvaluator(policies, alerts, new AlertEpisodes(alerts, events, Duration.ofHours(6)),
                owner, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ReplenishmentEligibility> provider(ReplenishmentEligibility bean) {
        ObjectProvider<ReplenishmentEligibility> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    private void stock(String sku, long usable, Integer reorderPoint, Integer safetyStock) {
        when(policies.thresholds(anyCollection(), any())).thenReturn(List.of(new ThresholdRow(sku, usable, reorderPoint, safetyStock)));
    }

    private List<InventoryAlertsRaised> published() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues().stream().map(InventoryAlertsRaised.class::cast).toList();
    }

    private static StockAlert openAlert(Kind kind, String sku, int qty, int threshold) {
        return StockAlert.open(java.util.UUID.randomUUID(), StockAlert.Key.stockLevel(kind, sku), qty, threshold, false,
                NOW.minusSeconds(7200));
    }

    @Test
    void stockAtTheReorderPointOpensAReorderAlert() {
        stock("A", 10, 10, null);
        assertThat(evaluator.evaluate(List.of("A"))).isEqualTo(1);
        List<InventoryAlertsRaised> raised = published();
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).kind()).isEqualTo("REORDER");
        assertThat(raised.get(0).warehouseId()).isNull();
        assertThat(raised.get(0).items().get(0).observedQty()).isEqualTo(10);
        assertThat(raised.get(0).items().get(0).thresholdQty()).isEqualTo(10);
    }

    @Test
    void stockOneAboveTheReorderPointOpensNothing() {
        stock("A", 11, 10, 5);
        assertThat(evaluator.evaluate(List.of("A"))).isZero();
        verify(alerts, never()).insertIfAbsent(any());
        assertThat(published()).isEmpty();
    }

    @Test
    void stockBelowTheSafetyStockOpensASafetyAlertEvenAboveTheReorderPoint() {
        stock("A", 4, 3, 5);
        assertThat(evaluator.evaluate(List.of("A"))).isEqualTo(1);
        assertThat(published()).extracting(InventoryAlertsRaised::kind).containsExactly("SAFETY");
    }

    @Test
    void stockAtTheSafetyStockIsNotBelowIt() {
        stock("A", 5, 3, 5);
        assertThat(evaluator.evaluate(List.of("A"))).isZero();
    }

    @Test
    void stockBelowBothThresholdsOpensBothAlertsAnnouncedPerKind() {
        stock("A", 2, 10, 5);
        assertThat(evaluator.evaluate(List.of("A"))).isEqualTo(2);
        assertThat(published()).extracting(InventoryAlertsRaised::kind).containsExactlyInAnyOrder("REORDER", "SAFETY");
    }

    @Test
    void severalSkusInOneCallAreOneAnnouncementPerKind() {
        when(policies.thresholds(anyCollection(), any())).thenReturn(List.of(
                new ThresholdRow("A", 1, 10, null), new ThresholdRow("B", 2, 10, null)));
        assertThat(evaluator.evaluate(List.of("B", "A"))).isEqualTo(2);
        List<InventoryAlertsRaised> raised = published();
        assertThat(raised).hasSize(1);
        assertThat(raised.get(0).items()).extracting(InventoryAlertsRaised.Item::sku).containsExactly("A", "B");
    }

    @Test
    void aSkuWithNoThresholdsAndNoStockIsNotAnAlertNullIsNotZero() {
        stock("A", 0, null, null);
        assertThat(evaluator.evaluate(List.of("A"))).isZero();
        verify(alerts, never()).insertIfAbsent(any());
    }

    @Test
    void aSkuWithAZeroReorderPointAndNothingLeftIsLow() {
        stock("A", 0, 0, null);
        assertThat(evaluator.evaluate(List.of("A"))).isEqualTo(1);
    }

    @Test
    void anOpenAlertWhoseConditionStillHoldsOnlyRefreshesAndSaysNothing() {
        StockAlert existing = openAlert(Kind.REORDER, "A", 8, 10);
        open.add(existing);
        stock("A", 6, 10, null);
        assertThat(evaluator.evaluate(List.of("A"))).isZero();
        assertThat(existing.observedQty()).isEqualTo(6);
        verify(alerts).update(existing);
        assertThat(published()).isEmpty();
    }

    @Test
    void stockThatClimbsAboveTheThresholdClosesTheAlertAsRecoveredWithoutAMessage() {
        StockAlert existing = openAlert(Kind.REORDER, "A", 8, 10);
        open.add(existing);
        stock("A", 40, 10, null);
        assertThat(evaluator.evaluate(List.of("A"))).isZero();
        assertThat(existing.status()).isEqualTo(StockAlert.Status.RESOLVED);
        assertThat(existing.resolution()).isEqualTo(StockAlert.Resolution.RECOVERED);
        assertThat(published()).isEmpty();
    }

    @Test
    void anAcknowledgedAlertAlsoRecovers() {
        StockAlert existing = openAlert(Kind.SAFETY, "A", 2, 5);
        existing.acknowledge(java.util.UUID.randomUUID(), null, NOW.minusSeconds(60));
        open.add(existing);
        stock("A", 9, 3, 5);
        evaluator.evaluate(List.of("A"));
        assertThat(existing.resolution()).isEqualTo(StockAlert.Resolution.RECOVERED);
    }

    @Test
    void removingTheThresholdClosesTheAlertAsUnconfiguredAndOnlyThatKind() {
        StockAlert reorder = openAlert(Kind.REORDER, "A", 8, 10);
        StockAlert safety = openAlert(Kind.SAFETY, "A", 2, 5);
        open.add(reorder);
        open.add(safety);
        stock("A", 1, null, 5);
        assertThat(evaluator.evaluate(List.of("A"))).isZero();
        assertThat(reorder.resolution()).isEqualTo(StockAlert.Resolution.UNCONFIGURED);
        assertThat(safety.status()).isEqualTo(StockAlert.Status.OPEN);
        assertThat(published()).isEmpty();
    }

    @Test
    void aSkuThatNoLongerHasAnInventoryItemClosesItsAlertsAsUnconfigured() {
        StockAlert reorder = openAlert(Kind.REORDER, "A", 8, 10);
        open.add(reorder);
        when(policies.thresholds(anyCollection(), any())).thenReturn(List.of());
        evaluator.evaluate(List.of("A"));
        assertThat(reorder.resolution()).isEqualTo(StockAlert.Resolution.UNCONFIGURED);
    }

    @Test
    void aReopeningInsideTheRetouchWindowIsOpenedButNotAnnounced() {
        stock("A", 3, 10, null);
        when(alerts.lastResolvedAt(any())).thenReturn(Optional.of(NOW.minus(Duration.ofHours(1))));
        assertThat(evaluator.evaluate(List.of("A"))).isZero();
        verify(alerts).insertIfAbsent(any());
        assertThat(published()).isEmpty();
    }

    @Test
    void aReopeningAfterTheWindowIsAnnouncedAgain() {
        stock("A", 3, 10, null);
        when(alerts.lastResolvedAt(any())).thenReturn(Optional.of(NOW.minus(Duration.ofHours(7))));
        assertThat(evaluator.evaluate(List.of("A"))).isEqualTo(1);
    }

    @Test
    void skusAreLockedInOrderBeforeAnythingIsRead() {
        stock("A", 1, 10, null);
        evaluator.evaluate(List.of("B", "A", "C"));
        var order = inOrder(alerts, policies);
        order.verify(alerts).lockEvaluation("A");
        order.verify(alerts).lockEvaluation("B");
        order.verify(alerts).lockEvaluation("C");
        order.verify(policies).thresholds(anyCollection(), eq(java.time.LocalDate.of(2026, 10, 14)));
    }

    @Test
    void theBusinessDateIsTheVietnameseOneNotUtc() {
        // 20:00 UTC on the 14th is already the 15th in Ho Chi Minh City.
        Instant lateUtc = Instant.parse("2026-10-14T20:00:00Z");
        StockAlertEvaluator late = new StockAlertEvaluator(policies, alerts,
                new AlertEpisodes(alerts, events, Duration.ofHours(6)), owner, Clock.fixed(lateUtc, ZoneOffset.UTC));
        stock("A", 1, 10, null);
        late.evaluate(List.of("A"));
        verify(policies).thresholds(anyCollection(), eq(java.time.LocalDate.of(2026, 10, 15)));
    }

    @Test
    void nothingToEvaluateTouchesNothing() {
        assertThat(evaluator.evaluate(List.of())).isZero();
        assertThat(evaluator.evaluate(null)).isZero();
        verify(policies, times(0)).thresholds(anyCollection(), any());
    }

    // ---- only a SKU that may be bought is watched (kltn-docs 01 BR-04, 02 BR-01)

    @Test
    void aSkuThatCannotBeBoughtGetsNoLowStockAlertEvenFarBelowItsThresholds() {
        stock("DRAFT-1", 0, 40, 15);
        when(eligibility.replenishable(anyCollection())).thenReturn(Set.of());

        assertThat(evaluator.evaluate(List.of("DRAFT-1"))).isZero();

        verify(alerts, never()).insertIfAbsent(any());
        assertThat(published()).isEmpty();
    }

    @Test
    void openAlertsOfASkuThatCanNoLongerBeBoughtCloseAsNotPurchasableWithoutAMessage() {
        stock("A", 0, 10, 5);
        StockAlert reorder = openAlert(Kind.REORDER, "A", 0, 10);
        StockAlert safety = openAlert(Kind.SAFETY, "A", 0, 5);
        open.add(reorder);
        open.add(safety);
        when(eligibility.replenishable(anyCollection())).thenReturn(Set.of());

        assertThat(evaluator.evaluate(List.of("A"))).isZero();

        assertThat(reorder.status()).isEqualTo(StockAlert.Status.RESOLVED);
        assertThat(reorder.resolution()).isEqualTo(StockAlert.Resolution.NOT_PURCHASABLE);
        assertThat(safety.resolution()).isEqualTo(StockAlert.Resolution.NOT_PURCHASABLE);
        verify(alerts, times(2)).update(any());
        verify(alerts, never()).insertIfAbsent(any());
        assertThat(published()).isEmpty();
    }

    @Test
    void inOneBatchOnlyTheSkusThatCanBeBoughtAreWatched() {
        when(policies.thresholds(anyCollection(), any())).thenReturn(List.of(
                new ThresholdRow("A", 0, 10, null), new ThresholdRow("B", 0, 10, null)));
        when(eligibility.replenishable(anyCollection())).thenReturn(Set.of("B"));

        assertThat(evaluator.evaluate(List.of("A", "B"))).isEqualTo(1);

        assertThat(published()).singleElement().satisfies(r ->
                assertThat(r.items()).extracting(InventoryAlertsRaised.Item::sku).containsExactly("B"));
        verify(eligibility).replenishable(eq(new java.util.TreeSet<>(List.of("A", "B"))));
    }

    @Test
    void withoutTheProductModuleEverySkuIsWatched() {
        // A module slice boots inventory alone: nothing knows the products, so nothing is filtered.
        StockAlertEvaluator alone = new StockAlertEvaluator(policies, alerts,
                new AlertEpisodes(alerts, events, Duration.ofHours(6)), provider(null), Clock.fixed(NOW, ZoneOffset.UTC));
        stock("A", 0, 10, null);

        assertThat(alone.evaluate(List.of("A"))).isEqualTo(1);
    }
}
