package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.StockReservationReleased;
import com.stockflow.contracts.StockReserved;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SCRUM-152: the soft, SKU-level reservation a checkout takes, and the allocation it turns into at release. */
class SkuReservationTest {

    private static final Sku SKU = new Sku("SOFA-3S-GREY");
    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(30);

    private static SkuReservation open(int quantity, int available) {
        return SkuReservation.open(SKU, UUID.randomUUID(), UUID.randomUUID(), Quantity.of(quantity), available, NOW, TTL);
    }

    private static StockItem stock(int onHand) {
        return new StockItem(StockItemId.newId(), SKU, new LocationId("HCM-A01-1-A"), null, null, Quantity.of(onHand),
                StockStatus.AVAILABLE, java.util.List.of(), 0L);
    }

    @Test
    @DisplayName("opens only within available-to-promise, on no location, with the checkout deadline")
    void opensWithinAtp() {
        SkuReservation reservation = open(3, 3);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(reservation.expiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(reservation.pullDomainEvents()).singleElement()
                .satisfies(e -> assertThat(((StockEvent) e).payload()).isInstanceOfSatisfying(StockReserved.class,
                        reserved -> assertThat(reserved.locationCode()).isNull()));
        assertThatThrownBy(() -> open(4, 3)).isInstanceOfSatisfying(InsufficientStockException.class,
                refused -> assertThat(refused.available()).isEqualTo(3));
        assertThatThrownBy(() -> open(1, -2)).isInstanceOfSatisfying(InsufficientStockException.class,
                refused -> assertThat(refused.available()).isZero());
    }

    @Test
    @DisplayName("expires, pins and re-arms like a checkout hold; release is idempotent and announced once")
    void lifecycle() {
        SkuReservation reservation = open(2, 10);
        assertThat(reservation.isExpiredAt(NOW.plus(TTL))).isTrue();
        assertThat(reservation.pin()).isTrue();
        assertThat(reservation.isExpiredAt(NOW.plus(Duration.ofDays(1)))).isFalse();
        assertThat(reservation.pin()).isFalse();
        assertThat(reservation.rearm(NOW.plus(TTL))).isTrue();
        reservation.pullDomainEvents();

        assertThat(reservation.release(ReleaseReason.RESERVATION_EXPIRED, NOW)).isTrue();
        assertThat(reservation.status()).isEqualTo(ReservationStatus.EXPIRED);
        assertThat(reservation.release(ReleaseReason.ORDER_CANCELLED, NOW)).isFalse();
        assertThat(reservation.pullDomainEvents()).singleElement()
                .satisfies(e -> assertThat(((StockEvent) e).payload()).isInstanceOf(StockReservationReleased.class));
        assertThatThrownBy(() -> reservation.markAllocated(NOW)).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE));
    }

    @Test
    @DisplayName("allocation: a pinned hold on the stock item pointing back at the reservation, which closes ALLOCATED")
    void allocation() {
        SkuReservation reservation = open(4, 10);
        StockItem item = stock(5);
        UUID request = UUID.randomUUID();

        Reservation allocation = item.allocate(reservation, request, Quantity.of(4), NOW);
        reservation.markAllocated(NOW);

        assertThat(allocation.allocatedFrom()).isEqualTo(reservation.id().value());
        assertThat(allocation.orderId()).isEqualTo(reservation.orderId());
        assertThat(allocation.isPinned()).isTrue();
        assertThat(allocation.rearm(NOW.plus(TTL))).as("an allocation never waits for payment again").isFalse();
        assertThat(item.reserved()).isEqualTo(Quantity.of(4));
        assertThat(item.allocate(reservation, request, Quantity.of(4), NOW)).as("replayed").isSameAs(allocation);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.ALLOCATED);
        assertThat(reservation.expiresAt()).isNull();
        assertThatThrownBy(() -> item.allocate(open(2, 10), UUID.randomUUID(), Quantity.of(2), NOW))
                .isInstanceOf(InsufficientStockException.class);
        assertThatThrownBy(() -> stock(5).allocate(SkuReservation.open(new Sku("OTHER"), UUID.randomUUID(),
                UUID.randomUUID(), Quantity.of(1), 1, NOW, TTL), UUID.randomUUID(), Quantity.of(1), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FEFO at allocation: earliest expiry first; without one, the earliest real receipt time")
    void fefoThenFifo() {
        LocationId bin = new LocationId("HCM-A01-1-A");
        var late = new StockAllocator.Candidate(StockItemId.newId(), bin, "L", java.time.LocalDate.of(2027, 1, 1),
                StockStatus.AVAILABLE, Quantity.of(5), Instant.parse("2026-01-01T00:00:00Z"));
        var early = new StockAllocator.Candidate(StockItemId.newId(), bin, "L", java.time.LocalDate.of(2026, 12, 1),
                StockStatus.AVAILABLE, Quantity.of(5), Instant.parse("2026-06-01T00:00:00Z"));
        var oldReceipt = new StockAllocator.Candidate(StockItemId.newId(), bin, null, null, StockStatus.AVAILABLE,
                Quantity.of(5), Instant.parse("2025-01-01T00:00:00Z"));
        var newReceipt = new StockAllocator.Candidate(StockItemId.newId(), bin, null, null, StockStatus.AVAILABLE,
                Quantity.of(5), Instant.parse("2025-06-01T00:00:00Z"));
        var expired = new StockAllocator.Candidate(StockItemId.newId(), bin, "L", java.time.LocalDate.of(2026, 10, 1),
                StockStatus.AVAILABLE, Quantity.of(50), Instant.parse("2024-01-01T00:00:00Z"));

        var plan = StockAllocator.plan("SOFA", java.util.List.of(newReceipt, late, oldReceipt, expired, early),
                Quantity.of(17), com.stockflow.inventory.api.RemovalStrategy.FEFO, java.time.LocalDate.of(2026, 10, 17));

        assertThat(plan).extracting(StockAllocator.AllocationLine::stockItemId).containsExactly(
                early.stockItemId(), late.stockItemId(), oldReceipt.stockItemId(), newReceipt.stockItemId());
        assertThat(plan.get(3).quantity()).isEqualTo(Quantity.of(2));
    }
}
