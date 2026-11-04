package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.contracts.InventoryAlertsRaised;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.domain.StockAlert.Key;
import com.stockflow.inventory.internal.domain.StockAlert.Kind;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.StockAlertRepository;
import com.stockflow.inventory.internal.repository.StockExpiryRepository;
import com.stockflow.inventory.internal.repository.StockExpiryRepository.LotRow;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The nightly sweep on its boundaries: what is blocked and what is not (a held lot), what the ledger says, which alerts
 * open, refresh and close (and how), and that one bad row does not stop the night.
 */
class ExpirySweeperTest {

    // 02:00 on the 14th in Ho Chi Minh City.
    private static final Instant NOW = Instant.parse("2026-10-13T19:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
    private static final UUID WAREHOUSE = UUID.randomUUID();
    private static final String LOCATION = "HCM-A01-1-B";

    private StockItemRepository stockItems;
    private StockMovementLog ledger;
    private StockExpiryRepository expiry;
    private StockAlertRepository alerts;
    private ApplicationEventPublisher events;
    private WarehouseService warehouses;
    private ExpirySweeper sweeper;
    private final List<StockAlert> open = new ArrayList<>();

    @BeforeEach
    void setUp() {
        stockItems = mock(StockItemRepository.class);
        ledger = mock(StockMovementLog.class);
        expiry = mock(StockExpiryRepository.class);
        alerts = mock(StockAlertRepository.class);
        events = mock(ApplicationEventPublisher.class);
        warehouses = mock(WarehouseService.class);
        when(warehouses.findLocation(LOCATION)).thenReturn(Optional.of(new StorageLocationView(UUID.randomUUID(),
                StorageLocationView.Kind.BIN, WAREHOUSE, LOCATION, StorageLocationView.StorageClass.NORMAL,
                StorageLocationView.Status.ACTIVE, StorageLocationView.Status.ACTIVE, true)));
        when(alerts.findOpenOfKinds(any(Kind[].class))).thenAnswer(i -> List.copyOf(open));
        when(alerts.insertIfAbsent(any())).thenReturn(true);
        when(alerts.lastResolvedAt(any())).thenReturn(Optional.empty());
        when(expiry.expiredAvailableIds(any(), any(), anyInt())).thenReturn(List.of());
        when(expiry.lots(any(), any())).thenReturn(List.of());
        TransactionTemplate inline = new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
        sweeper = new ExpirySweeper(stockItems, ledger, expiry, alerts,
                new AlertEpisodes(alerts, events, Duration.ofHours(6)), warehouses, inline,
                Clock.fixed(NOW, ZoneOffset.UTC), 30);
    }

    private static StockItem row(UUID id, StockStatus status, int onHand, LocalDate expiryDate, int held) {
        List<Reservation> holds = held == 0 ? List.of() : List.of(new Reservation(ReservationId.newId(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Quantity.of(held), NOW, null, ReservationStatus.HELD, null, null));
        return new StockItem(new StockItemId(id), new Sku("SKU-1"), new LocationId(LOCATION), "LOT-1", expiryDate,
                Quantity.of(onHand), status, holds, 0L);
    }

    private static LotRow lot(String sku, LocalDate expiryDate, long qty, boolean impaired) {
        return new LotRow("HCM", LOCATION, sku, "LOT-1", expiryDate, qty, impaired);
    }

    private List<InventoryAlertsRaised> published() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.atLeast(0)).publishEvent(captor.capture());
        return captor.getAllValues().stream().map(InventoryAlertsRaised.class::cast).toList();
    }

    // ------------------------------------------------------------------ blocking

    @Test
    void anExpiredAvailableRowIsMovedToExpiredAndTheLedgerSaysSo() {
        UUID id = UUID.randomUUID();
        StockItem item = row(id, StockStatus.AVAILABLE, 7, TODAY.minusDays(1), 0);
        when(expiry.expiredAvailableIds(eq(TODAY), any(), anyInt())).thenReturn(List.of(id)).thenReturn(List.of());
        when(stockItems.findByIdForUpdate(new StockItemId(id))).thenReturn(Optional.of(item));

        ExpirySweeper.Result result = sweeper.sweep();

        assertThat(result.blocked()).isEqualTo(1);
        assertThat(item.status()).isEqualTo(StockStatus.EXPIRED);
        verify(stockItems).save(item);
        ArgumentCaptor<StockMovement> line = ArgumentCaptor.forClass(StockMovement.class);
        verify(ledger).append(line.capture());
        assertThat(line.getValue().type()).isEqualTo(StockMovement.MovementType.STATUS_CHANGE);
        assertThat(line.getValue().referenceType()).isEqualTo(StockMovement.ReferenceType.EXPIRY_SWEEP);
        assertThat(line.getValue().referenceId()).isEqualTo(id);
        assertThat(line.getValue().quantity()).isEqualTo(7);
        assertThat(line.getValue().status()).isEqualTo(StockStatus.AVAILABLE);
        assertThat(line.getValue().toStatus()).isEqualTo(StockStatus.EXPIRED);
        assertThat(line.getValue().from()).isEqualTo(line.getValue().to());
        assertThat(line.getValue().reason()).isEqualTo("EXPIRY_SWEEP:2026-10-14");
        assertThat(line.getValue().occurredAt()).isEqualTo(NOW);
    }

    @Test
    void aRowHeldByAReservationIsLeftAlone() {
        UUID id = UUID.randomUUID();
        StockItem item = row(id, StockStatus.AVAILABLE, 7, TODAY.minusDays(1), 2);
        when(expiry.expiredAvailableIds(eq(TODAY), any(), anyInt())).thenReturn(List.of(id)).thenReturn(List.of());
        when(stockItems.findByIdForUpdate(new StockItemId(id))).thenReturn(Optional.of(item));

        assertThat(sweeper.sweep().blocked()).isZero();
        assertThat(item.status()).isEqualTo(StockStatus.AVAILABLE);
        verify(stockItems, never()).save(any());
        verify(ledger, never()).append(any());
    }

    @Test
    void underTheLockARowThatIsNoLongerExpiredAvailableOrFullIsSkipped() {
        UUID moved = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        when(expiry.expiredAvailableIds(eq(TODAY), any(), anyInt())).thenReturn(List.of(moved, empty, fresh, gone)).thenReturn(List.of());
        when(stockItems.findByIdForUpdate(new StockItemId(moved))).thenReturn(Optional.of(row(moved, StockStatus.QUARANTINE, 5, TODAY.minusDays(2), 0)));
        when(stockItems.findByIdForUpdate(new StockItemId(empty))).thenReturn(Optional.of(row(empty, StockStatus.AVAILABLE, 0, TODAY.minusDays(2), 0)));
        when(stockItems.findByIdForUpdate(new StockItemId(fresh))).thenReturn(Optional.of(row(fresh, StockStatus.AVAILABLE, 5, TODAY, 0)));
        when(stockItems.findByIdForUpdate(new StockItemId(gone))).thenReturn(Optional.empty());

        assertThat(sweeper.sweep().blocked()).isZero();
        verify(stockItems, never()).save(any());
    }

    @Test
    void oneRowThatFailsDoesNotStopTheRestOfTheNight() {
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        StockItem item = row(good, StockStatus.AVAILABLE, 3, TODAY.minusDays(5), 0);
        when(expiry.expiredAvailableIds(eq(TODAY), any(), anyInt())).thenReturn(List.of(bad, good)).thenReturn(List.of());
        when(stockItems.findByIdForUpdate(new StockItemId(bad))).thenThrow(new IllegalStateException("lock timeout"));
        when(stockItems.findByIdForUpdate(new StockItemId(good))).thenReturn(Optional.of(item));

        assertThat(sweeper.sweep().blocked()).isEqualTo(1);
        assertThat(item.status()).isEqualTo(StockStatus.EXPIRED);
    }

    // ------------------------------------------------------------------ alerts

    @Test
    void lotsNearExpiryAndExpiredOpenTheirAlertsAnnouncedOncePerKindAndWarehouse() {
        when(expiry.lots(TODAY, TODAY.plusDays(30))).thenReturn(List.of(
                lot("A", TODAY.minusDays(1), 4, true),   // expired, still held
                lot("B", TODAY.minusDays(9), 2, false),  // expired
                lot("C", TODAY, 5, false),                // today: still sellable, expiring
                lot("D", TODAY.plusDays(30), 6, false))); // last day of the window

        ExpirySweeper.Result result = sweeper.sweep();

        assertThat(result.announced()).isEqualTo(4);
        assertThat(result.impaired()).isEqualTo(1);
        List<InventoryAlertsRaised> raised = published();
        assertThat(raised).extracting(InventoryAlertsRaised::kind).containsExactlyInAnyOrder("EXPIRED", "EXPIRING");
        InventoryAlertsRaised expired = raised.stream().filter(e -> e.kind().equals("EXPIRED")).findFirst().orElseThrow();
        assertThat(expired.warehouseId()).isEqualTo(WAREHOUSE);
        assertThat(expired.items()).extracting(InventoryAlertsRaised.Item::sku).containsExactly("A", "B");
        assertThat(expired.items().get(0).reservationImpaired()).isTrue();
        assertThat(expired.items().get(1).reservationImpaired()).isFalse();
        InventoryAlertsRaised expiring = raised.stream().filter(e -> e.kind().equals("EXPIRING")).findFirst().orElseThrow();
        assertThat(expiring.items()).extracting(InventoryAlertsRaised.Item::sku).containsExactly("C", "D");
        assertThat(expiring.items().get(0).thresholdQty()).isNull();
    }

    @Test
    void impairmentIsOnlyEverReportedForExpiredLots() {
        when(expiry.lots(any(), any())).thenReturn(List.of(lot("A", TODAY.plusDays(3), 4, true)));
        ExpirySweeper.Result result = sweeper.sweep();
        assertThat(result.impaired()).isZero();
        assertThat(published().get(0).items().get(0).reservationImpaired()).isFalse();
    }

    @Test
    void aSecondRunAnnouncesNothingBecauseTheAlertsAreOpenAlready() {
        StockAlert existing = StockAlert.open(UUID.randomUUID(),
                Key.expiry(Kind.EXPIRING, "C", WAREHOUSE, "LOT-1", TODAY.plusDays(5)), 5, null, false, NOW.minusSeconds(86400));
        open.add(existing);
        when(expiry.lots(any(), any())).thenReturn(List.of(lot("C", TODAY.plusDays(5), 5, false)));

        ExpirySweeper.Result result = sweeper.sweep();

        assertThat(result.announced()).isZero();
        assertThat(result.resolved()).isZero();
        verify(alerts, never()).insertIfAbsent(any());
        verify(alerts, never()).update(any());
        assertThat(published()).isEmpty();
    }

    @Test
    void openNumbersAreRefreshedAndAHeldLotBecomesFreeWhenItsReservationGoes() {
        StockAlert existing = StockAlert.open(UUID.randomUUID(),
                Key.expiry(Kind.EXPIRED, "A", WAREHOUSE, "LOT-1", TODAY.minusDays(1)), 4, null, true, NOW.minusSeconds(86400));
        open.add(existing);
        when(expiry.lots(any(), any())).thenReturn(List.of(lot("A", TODAY.minusDays(1), 3, false)));

        sweeper.sweep();

        assertThat(existing.observedQty()).isEqualTo(3);
        assertThat(existing.reservationImpaired()).isFalse();
        verify(alerts).update(existing);
        assertThat(published()).isEmpty();
    }

    @Test
    void anExpiryAlertWhoseStockIsGoneClosesAsStockGone() {
        StockAlert expiring = StockAlert.open(UUID.randomUUID(),
                Key.expiry(Kind.EXPIRING, "C", WAREHOUSE, "LOT-1", TODAY.plusDays(5)), 5, null, false, NOW.minusSeconds(86400));
        StockAlert expired = StockAlert.open(UUID.randomUUID(),
                Key.expiry(Kind.EXPIRED, "A", WAREHOUSE, "LOT-1", TODAY.minusDays(1)), 5, null, false, NOW.minusSeconds(86400));
        open.add(expiring);
        open.add(expired);

        ExpirySweeper.Result result = sweeper.sweep();

        assertThat(result.resolved()).isEqualTo(2);
        assertThat(expiring.resolution()).isEqualTo(StockAlert.Resolution.STOCK_GONE);
        assertThat(expired.resolution()).isEqualTo(StockAlert.Resolution.STOCK_GONE);
        assertThat(published()).isEmpty();
    }

    @Test
    void aNearExpiryAlertWhoseLotHasNowExpiredClosesAsExpiredOutAndTheExpiredAlertOpens() {
        // The lot was in the window yesterday (expiry = yesterday) and is expired today.
        StockAlert expiring = StockAlert.open(UUID.randomUUID(),
                Key.expiry(Kind.EXPIRING, "C", WAREHOUSE, "LOT-1", TODAY.minusDays(1)), 5, null, false, NOW.minusSeconds(86400));
        open.add(expiring);
        when(expiry.lots(any(), any())).thenReturn(List.of(lot("C", TODAY.minusDays(1), 5, false)));

        ExpirySweeper.Result result = sweeper.sweep();

        assertThat(expiring.resolution()).isEqualTo(StockAlert.Resolution.EXPIRED_OUT);
        assertThat(result.announced()).isEqualTo(1);
        assertThat(published()).extracting(InventoryAlertsRaised::kind).containsExactly("EXPIRED");
    }

    @Test
    void aLotInALocationWithNoWarehouseIsSkippedNotFatal() {
        when(expiry.lots(any(), any())).thenReturn(List.of(new LotRow("ZZ", "ZZ-NOPE", "A", "L", TODAY.plusDays(2), 3, false)));
        when(warehouses.findLocation("ZZ-NOPE")).thenReturn(Optional.empty());
        assertThat(sweeper.sweep().announced()).isZero();
    }

    @Test
    void aWarehouseIsResolvedOncePerPrefixNotOncePerLot() {
        when(expiry.lots(any(), any())).thenReturn(List.of(lot("A", TODAY.plusDays(1), 1, false), lot("B", TODAY.plusDays(2), 1, false),
                lot("C", TODAY.plusDays(3), 1, false)));
        sweeper.sweep();
        verify(warehouses, org.mockito.Mockito.times(1)).findLocation(LOCATION);
    }

    @Test
    void aReopeningInsideTheRetouchWindowIsNotAnnounced() {
        when(expiry.lots(any(), any())).thenReturn(List.of(lot("A", TODAY.plusDays(3), 4, false)));
        when(alerts.lastResolvedAt(any())).thenReturn(Optional.of(NOW.minus(Duration.ofHours(2))));
        assertThat(sweeper.sweep().announced()).isZero();
        verify(alerts).insertIfAbsent(any());
    }
}
