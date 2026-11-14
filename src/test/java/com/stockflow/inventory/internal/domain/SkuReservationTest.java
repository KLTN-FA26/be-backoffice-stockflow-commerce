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

/**
 * SCRUM-152 / 4B-7: the soft, SKU-level hold an order of a contract takes at its shipping warehouse, and the allocation
 * it turns into at release. The checkout reservation with a payment deadline is gone (V20261117000300).
 */
class SkuReservationTest {

    private static final Sku SKU = new Sku("SOFA-3S-GREY");
    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");

    private static SkuReservation hold(int quantity, int available) {
        return SkuReservation.holdAt("HCM", SKU, UUID.randomUUID(), UUID.randomUUID(), Quantity.of(quantity), available,
                NOW);
    }

    /** A carton's row: only a carton's row takes an allocation (kltn-docs 20 BR-14). */
    private static StockItem stock(int onHand) {
        return new StockItem(StockItemId.newId(), SKU, new LocationId("HCM-A01-1-A"), "LOT-1", Quantity.of(onHand),
                StockStatus.AVAILABLE, java.util.List.of(), 0L, NOW, UUID.randomUUID(),
                new StockPackageId(UUID.randomUUID()));
    }

    @Test
    @DisplayName("holds only within available-to-promise, on no location, announced once")
    void holdsWithinAtp() {
        SkuReservation reservation = hold(3, 3);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(reservation.pullDomainEvents()).singleElement()
                .satisfies(e -> assertThat(((StockEvent) e).payload()).isInstanceOfSatisfying(StockReserved.class,
                        reserved -> assertThat(reserved.locationCode()).isNull()));
        assertThatThrownBy(() -> hold(4, 3)).isInstanceOfSatisfying(InsufficientStockException.class,
                refused -> assertThat(refused.available()).isEqualTo(3));
        assertThatThrownBy(() -> hold(1, -2)).isInstanceOfSatisfying(InsufficientStockException.class,
                refused -> assertThat(refused.available()).isZero());
    }

    @Test
    @DisplayName("WF4-COUNT-EDGE a: a released hold at a warehouse is replaced by a smaller one for the same order, there")
    void keepingWhatStockStillCovers() {
        SkuReservation reservation = hold(10, 10);
        assertThatThrownBy(() -> reservation.keeping(8, UUID.randomUUID(), NOW))
                .as("a live hold is released first").isInstanceOf(IllegalStateException.class);
        reservation.release(ReleaseReason.SHORT_PICKED, NOW);
        UUID request = UUID.randomUUID();

        SkuReservation kept = reservation.keeping(8, request, NOW.plusSeconds(1));

        assertThat(kept.id()).isNotEqualTo(reservation.id());
        assertThat(kept.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(kept.quantity()).isEqualTo(Quantity.of(8));
        assertThat(kept.orderId()).isEqualTo(reservation.orderId());
        assertThat(kept.warehouseCode()).isEqualTo("HCM");
        assertThat(kept.requestId()).isEqualTo(request);
        assertThat(kept.expiresAt()).isNull();
        assertThatThrownBy(() -> reservation.keeping(10, UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservation.keeping(0, UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("4B-7 (20 §5.6, BR-17): held at a warehouse for an order of a contract, with no deadline")
    void heldAtAWarehouse() {
        SkuReservation reservation = SkuReservation.holdAt("HCM", SKU, UUID.randomUUID(), UUID.randomUUID(),
                Quantity.of(100), 150, NOW);
        assertThat(reservation.warehouseCode()).isEqualTo("HCM");
        assertThat(reservation.expiresAt()).as("no deadline: it lasts until release or cancellation").isNull();
        assertThat(reservation.isExpiredAt(NOW.plus(Duration.ofDays(365)))).isFalse();
        assertThat(reservation.status()).isEqualTo(ReservationStatus.HELD);
        assertThatThrownBy(() -> SkuReservation.holdAt("HCM", SKU, UUID.randomUUID(), UUID.randomUUID(),
                Quantity.of(100), 99, NOW)).isInstanceOfSatisfying(InsufficientStockException.class,
                refused -> assertThat(refused.available()).isEqualTo(99));
    }

    @Test
    @DisplayName("release is idempotent and announced once; a released hold is not allocated")
    void lifecycle() {
        SkuReservation reservation = hold(2, 10);
        reservation.pullDomainEvents();

        assertThat(reservation.release(ReleaseReason.ORDER_CANCELLED, NOW)).isTrue();
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(reservation.release(ReleaseReason.MANUAL_OVERRIDE, NOW)).isFalse();
        assertThat(reservation.releaseReason()).isEqualTo(ReleaseReason.ORDER_CANCELLED);
        assertThat(reservation.pullDomainEvents()).singleElement()
                .satisfies(e -> assertThat(((StockEvent) e).payload()).isInstanceOf(StockReservationReleased.class));
        assertThatThrownBy(() -> reservation.markAllocated(NOW)).isInstanceOfSatisfying(BusinessException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE));
    }

    @Test
    @DisplayName("a HELD hold of before V20261117000300 still reads as expired past its deadline; a hold of now never")
    void legacyDeadline() {
        SkuReservation legacy = new SkuReservation(ReservationId.newId(), SKU, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Quantity.of(2), NOW, NOW.plus(Duration.ofMinutes(30)), ReservationStatus.HELD, null,
                null);
        assertThat(legacy.isExpiredAt(NOW.plus(Duration.ofMinutes(30)))).isTrue();
        assertThat(legacy.isExpiredAt(NOW)).isFalse();
        assertThat(hold(2, 10).isExpiredAt(NOW.plus(Duration.ofDays(365)))).isFalse();
    }

    @Test
    @DisplayName("allocation: a pinned hold on the stock item pointing back at the reservation, which closes ALLOCATED")
    void allocation() {
        SkuReservation reservation = hold(4, 10);
        StockItem item = stock(5);
        UUID request = UUID.randomUUID();

        Reservation allocation = item.allocate(reservation, request, Quantity.of(4), NOW);
        reservation.markAllocated(NOW);

        assertThat(allocation.allocatedFrom()).isEqualTo(reservation.id().value());
        assertThat(allocation.orderId()).isEqualTo(reservation.orderId());
        assertThat(allocation.isPinned()).isTrue();
        assertThat(item.reserved()).isEqualTo(Quantity.of(4));
        assertThat(item.allocate(reservation, request, Quantity.of(4), NOW)).as("replayed").isSameAs(allocation);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.ALLOCATED);
        assertThat(reservation.expiresAt()).isNull();
        assertThatThrownBy(() -> item.allocate(hold(2, 10), UUID.randomUUID(), Quantity.of(2), NOW))
                .isInstanceOf(InsufficientStockException.class);
        assertThatThrownBy(() -> stock(5).allocate(SkuReservation.holdAt("HCM", new Sku("OTHER"), UUID.randomUUID(),
                UUID.randomUUID(), Quantity.of(1), 1, NOW), UUID.randomUUID(), Quantity.of(1), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("FIFO at allocation: the earliest real receipt time first, whole cartons (kltn-docs 01 BR-17)")
    void fifoAtAllocation() {
        LocationId bin = new LocationId("HCM-A01-1-A");
        var late = carton(bin, "L", "2026-01-01T00:00:00Z");
        var early = carton(bin, "L", "2024-01-01T00:00:00Z");
        var oldReceipt = carton(bin, null, "2025-01-01T00:00:00Z");
        var newReceipt = carton(bin, null, "2025-06-01T00:00:00Z");

        var plan = StockAllocator.plan("SOFA", java.util.List.of(newReceipt, late, oldReceipt, early),
                Quantity.of(20));

        assertThat(plan.lines()).extracting(StockAllocator.AllocationLine::stockItemId).containsExactly(
                early.stockItemId(), oldReceipt.stockItemId(), newReceipt.stockItemId(), late.stockItemId());
        assertThat(plan.isShort()).isFalse();
    }

    /** A sealed carton of 5. */
    private static StockAllocator.Candidate carton(LocationId bin, String lot, String received) {
        return new StockAllocator.Candidate(StockItemId.newId(), bin, lot, StockStatus.AVAILABLE,
                Quantity.of(5), Instant.parse(received), java.util.UUID.randomUUID(), true);
    }
}
