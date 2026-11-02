package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.contracts.StockReservationReleased;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.SkuReservation;
import com.stockflow.inventory.internal.domain.SkuReservationRepository;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** BR-014 (kltn-docs 14 BR-07): an unpaid checkout hold is given up once its deadline passes. */
class ReservationSweeperTest {

    private static final Instant LATER = NOW.plus(Duration.ofMinutes(31));

    private final StockItemRepository stockItems = mock(StockItemRepository.class);
    private final SkuReservationRepository skuReservations = mock(SkuReservationRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final ReservationSweeper sweeper = new ReservationSweeper(stockItems, skuReservations,
            new InventoryEventPublisher(publisher), Clock.fixed(LATER, ZoneOffset.UTC));

    private static SkuReservation skuHold(UUID orderId, int units) {
        return SkuReservation.open(new Sku("CUP-12OZ"), orderId, UUID.randomUUID(), Quantity.of(units), 100, NOW,
                Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("an expired SKU reservation is closed as EXPIRED and announced, so order can cancel the unpaid order")
    void expiresSkuReservation() {
        UUID orderId = UUID.randomUUID();
        SkuReservation hold = skuHold(orderId, 12);
        hold.pullDomainEvents();
        when(skuReservations.findExpiredForUpdate(LATER, 200)).thenReturn(List.of(hold));
        when(stockItems.findWithReservationsExpiredBefore(LATER, 200)).thenReturn(List.of());

        sweeper.releaseExpiredReservations();

        assertThat(hold.status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(hold.releaseReason()).isEqualTo(ReleaseReason.RESERVATION_EXPIRED);
        assertThat(hold.closedAt()).isEqualTo(LATER);
        verify(skuReservations).save(hold);
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(publisher).publishEvent(published.capture());
        assertThat(published.getValue()).isInstanceOfSatisfying(StockReservationReleased.class, released -> {
            assertThat(released.orderId()).isEqualTo(orderId);
            assertThat(released.quantity()).isEqualTo(12);
        });
    }

    @Test
    @DisplayName("a reservation another actor already closed is left alone: nothing saved, nothing announced")
    void alreadyClosedIsSkipped() {
        SkuReservation hold = skuHold(UUID.randomUUID(), 5);
        hold.release(ReleaseReason.ORDER_CANCELLED, NOW.plusSeconds(5));
        hold.pullDomainEvents();
        when(skuReservations.findExpiredForUpdate(LATER, 200)).thenReturn(List.of(hold));
        when(stockItems.findWithReservationsExpiredBefore(LATER, 200)).thenReturn(List.of());

        sweeper.releaseExpiredReservations();

        verify(skuReservations, never()).save(any());
        verify(publisher, never()).publishEvent(any(Object.class));
        assertThat(hold.releaseReason()).isEqualTo(ReleaseReason.ORDER_CANCELLED);
    }

    @Test
    @DisplayName("a hold older than the 30-minute window is released from the stock item, which gets its units back")
    void releasesLegacyStockItemHold() {
        StockItem item = InventoryFixtures.available("HCM-A01-1-A", 50);
        item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(20), NOW);
        item.pullDomainEvents();
        assertThat(item.available().value()).isEqualTo(30);
        when(skuReservations.findExpiredForUpdate(LATER, 200)).thenReturn(List.of());
        when(stockItems.findWithReservationsExpiredBefore(LATER, 200)).thenReturn(List.of(item));

        sweeper.releaseExpiredReservations();

        assertThat(item.available().value()).isEqualTo(50);
        assertThat(item.onHand().value()).isEqualTo(50);
        verify(stockItems).save(item);
        verify(publisher).publishEvent(any(StockReservationReleased.class));
    }

    @Test
    @DisplayName("a hold still inside its window is not released")
    void liveHoldIsKept() {
        StockItem item = InventoryFixtures.available("HCM-A01-1-A", 50);
        item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(20), LATER.minusSeconds(60));
        when(skuReservations.findExpiredForUpdate(LATER, 200)).thenReturn(List.of());
        when(stockItems.findWithReservationsExpiredBefore(LATER, 200)).thenReturn(List.of(item));

        sweeper.releaseExpiredReservations();

        assertThat(item.reserved().value()).isEqualTo(20);
    }

    @Test
    @DisplayName("with nothing expired, nothing is saved or published")
    void nothingToDo() {
        when(skuReservations.findExpiredForUpdate(eq(LATER), eq(200))).thenReturn(List.of());
        when(stockItems.findWithReservationsExpiredBefore(LATER, 200)).thenReturn(List.of());

        sweeper.releaseExpiredReservations();

        verify(skuReservations, never()).save(any());
        verify(stockItems, never()).save(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("each run is bounded to 200 aggregates so a backlog cannot stall live checkouts")
    void batchesAreBounded() {
        sweeper.releaseExpiredReservations();

        verify(skuReservations).findExpiredForUpdate(LATER, 200);
        verify(stockItems).findWithReservationsExpiredBefore(LATER, 200);
    }
}
