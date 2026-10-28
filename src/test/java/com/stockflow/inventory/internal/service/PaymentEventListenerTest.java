package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.contracts.PaymentFailed;
import com.stockflow.contracts.StockReservationReleased;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.SkuReservation;
import com.stockflow.inventory.internal.domain.SkuReservationRepository;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A failed payment puts the order's stock back on sale - except what production or packing owns (BR-PRD-10). */
class PaymentEventListenerTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final PaymentFailed FAILED = new PaymentFailed(ORDER, UUID.randomUUID(), "CARD_DECLINED", "declined");

    private final StockItemRepository stockItems = mock(StockItemRepository.class);
    private final SkuReservationRepository skuReservations = mock(SkuReservationRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final StockOperations operations = mock(StockOperations.class);
    private final PaymentEventListener listener = new PaymentEventListener(stockItems, skuReservations,
            new InventoryEventPublisher(publisher), Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC), operations);

    private static SkuReservation skuHold(int units) {
        return SkuReservation.open(new Sku("CUP-12OZ"), ORDER, UUID.randomUUID(), Quantity.of(units), 100, NOW,
                Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("the order's SKU reservations are released as PAYMENT_FAILED and announced")
    void releasesSkuReservations() {
        SkuReservation hold = skuHold(10);
        hold.pullDomainEvents();
        when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(hold));
        when(stockItems.findWithReservationsForOrder(ORDER)).thenReturn(List.of());

        listener.on(FAILED);

        assertThat(hold.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(hold.releaseReason()).isEqualTo(ReleaseReason.PAYMENT_FAILED);
        verify(skuReservations).save(hold);
        verify(publisher).publishEvent(any(StockReservationReleased.class));
    }

    @Test
    @DisplayName("redelivery is harmless: a reservation already closed is not saved or announced again")
    void redeliveryIsIdempotent() {
        SkuReservation hold = skuHold(10);
        hold.release(ReleaseReason.PAYMENT_FAILED, NOW);
        hold.pullDomainEvents();
        when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(hold));

        listener.on(FAILED);

        verify(skuReservations, never()).save(any());
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("holds on stock rows (placed before SKU reservations existed) are released and the units are free again")
    void releasesStockItemHolds() {
        StockItem item = InventoryFixtures.available("HCM-A01-1-A", 40);
        item.reserve(ORDER, UUID.randomUUID(), UUID.randomUUID(), Quantity.of(15), NOW);
        item.pullDomainEvents();
        when(stockItems.findWithReservationsForOrder(ORDER)).thenReturn(List.of(item));

        listener.on(FAILED);

        assertThat(item.available().value()).isEqualTo(40);
        assertThat(item.reserved().value()).isZero();
        assertThat(item.reservations()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(r.releaseReason()).isEqualTo(ReleaseReason.PAYMENT_FAILED);
        });
        verify(stockItems).save(item);
    }

    @Test
    @DisplayName("only this order's holds are released: another order's hold on the same row stays")
    void otherOrdersHoldIsKept() {
        StockItem item = InventoryFixtures.available("HCM-A01-1-A", 40);
        item.reserve(ORDER, UUID.randomUUID(), UUID.randomUUID(), Quantity.of(15), NOW);
        item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(5), NOW);
        when(stockItems.findWithReservationsForOrder(ORDER)).thenReturn(List.of(item));

        listener.on(FAILED);

        assertThat(item.reserved().value()).isEqualTo(5);
    }

    @Test
    @DisplayName("BR-PRD-10: printed goods waiting in packing are packed or scrapped, never freed by a payment")
    void finishedCustomGoodsAreKept() {
        StockItem item = InventoryFixtures.available("HCM-A01-1-A", 40);
        item.reserve(ORDER, UUID.randomUUID(), UUID.randomUUID(), Quantity.of(15), NOW);
        when(stockItems.findWithReservationsForOrder(ORDER)).thenReturn(List.of(item));
        when(operations.isFinishedCustomGoods(any(), any())).thenReturn(true);

        listener.on(FAILED);

        assertThat(item.reserved().value()).isEqualTo(15);
    }

    @Test
    @DisplayName("blanks in the print shop are closed by production, not by a payment: the row is left untouched")
    void inProductionIsSkipped() {
        StockItem inPrintShop = new StockItem(StockItemId.newId(), new Sku("CUP-12OZ"), new LocationId("HCM-PRD01"),
                null, null, Quantity.of(40), StockStatus.IN_PRODUCTION, List.of(), 0L);
        when(stockItems.findWithReservationsForOrder(ORDER)).thenReturn(List.of(inPrintShop));

        listener.on(FAILED);

        verify(stockItems, never()).save(any());
    }

    @Test
    @DisplayName("an order that held nothing is a no-op")
    void nothingHeld() {
        when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of());
        when(stockItems.findWithReservationsForOrder(ORDER)).thenReturn(List.of());

        listener.on(FAILED);

        verify(stockItems, never()).save(any());
        verify(skuReservations, never()).save(any());
    }
}
