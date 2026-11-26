package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.StockReservationReleased;
import com.stockflow.contracts.StockReserved;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SCRUM-152 / 4B-7: the soft, SKU-level hold an order of a contract takes at its shipping warehouse, and the allocation
 * it turns into at release. The checkout reservation with a payment deadline is gone (V20261117000300), and a hold
 * counts cartons of one count (ADR-0011, V20261231000500): its units are derived.
 */
class SkuReservationTest {

    private static final Sku SKU = new Sku("LY-GIAY-DEMO-12OZ-XANH");
    private static final Instant NOW = Instant.parse("2026-10-17T03:00:00Z");

    /** Cartons of 5, the count of {@link #stock}'s carton. */
    private static final int PER_CARTON = 5;

    /** A hold of {@code cartons} cartons of 5, {@code availableCartons} such cartons being left to promise. */
    private static SkuReservation hold(int cartons, int availableCartons) {
        return SkuReservation.holdAt("HCM", SKU, UUID.randomUUID(), UUID.randomUUID(), cartons, PER_CARTON,
                availableCartons, NOW);
    }

    /** A free AVAILABLE carton of {@code units}: what takes an allocation (kltn-docs 20 BR-14). */
    private static StockPackage stock(int units) {
        return new StockPackage(new StockPackageId(UUID.randomUUID()), "LPN-SKU-RES-1", SKU, UUID.randomUUID(),
                UUID.randomUUID(), units, NOW, new LocationId("HCM-A01-1-A"), StockPackageStatus.IN_STOCK,
                StockStatus.AVAILABLE, UUID.randomUUID(), "LOT-1", NOW, null, null, java.util.List.of(), 0L);
    }

    @Test
    @DisplayName("holds only within available-to-promise, on no location, announced once")
    void holdsWithinAtp() {
        SkuReservation reservation = hold(3, 3);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(reservation.cartons()).isEqualTo(3);
        assertThat(reservation.unitsPerCarton()).isEqualTo(PER_CARTON);
        assertThat(reservation.quantity()).as("units are derived: cartons times their count").isEqualTo(Quantity.of(15));
        assertThat(reservation.pullDomainEvents()).singleElement()
                .satisfies(e -> assertThat(((StockEvent) e).payload()).isInstanceOfSatisfying(StockReserved.class,
                        reserved -> {
                            assertThat(reserved.locationCode()).isNull();
                            assertThat(reserved.quantity()).as("the event still speaks units").isEqualTo(15);
                        }));
        assertThatThrownBy(() -> hold(4, 3)).isInstanceOfSatisfying(InsufficientStockException.class, refused -> {
            assertThat(refused.requested()).as("a shortage speaks units").isEqualTo(20);
            assertThat(refused.available()).isEqualTo(15);
        });
        assertThatThrownBy(() -> hold(1, -2)).isInstanceOfSatisfying(InsufficientStockException.class,
                refused -> assertThat(refused.available()).isZero());
    }

    @Test
    @DisplayName("ADR-0011: a hold is at least one carton of at least one unit, and knows what a whole carton of it is")
    void countsCartons() {
        assertThatThrownBy(() -> hold(0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SkuReservation.holdAt("HCM", SKU, UUID.randomUUID(), UUID.randomUUID(), 1, 0, 3, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        SkuReservation reservation = hold(3, 3);
        assertThat(reservation.isWholeCartons(10)).isTrue();
        assertThat(reservation.isWholeCartons(0)).isTrue();
        assertThat(reservation.isWholeCartons(7)).as("7 units is not a number of cartons of 5").isFalse();
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
        assertThat(kept.cartons()).isEqualTo(8);
        assertThat(kept.unitsPerCarton()).as("cartons of the same count as the hold it replaces").isEqualTo(PER_CARTON);
        assertThat(kept.quantity()).isEqualTo(Quantity.of(40));
        assertThat(kept.orderId()).isEqualTo(reservation.orderId());
        assertThat(kept.warehouseCode()).isEqualTo("HCM");
        assertThat(kept.requestId()).isEqualTo(request);
        assertThatThrownBy(() -> reservation.keeping(10, UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reservation.keeping(0, UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("4B-7 (20 §5.6, BR-17): held at a warehouse for an order of a contract, with no deadline")
    void heldAtAWarehouse() {
        SkuReservation reservation = SkuReservation.holdAt("HCM", SKU, UUID.randomUUID(), UUID.randomUUID(),
                100, 1000, 150, NOW);
        assertThat(reservation.warehouseCode()).isEqualTo("HCM");
        assertThat(reservation.status()).as("no deadline: it lasts until release or cancellation")
                .isEqualTo(ReservationStatus.HELD);
        assertThat(reservation.quantity()).isEqualTo(Quantity.of(100_000));
        assertThatThrownBy(() -> SkuReservation.holdAt("HCM", SKU, UUID.randomUUID(), UUID.randomUUID(),
                100, 1000, 99, NOW)).isInstanceOfSatisfying(InsufficientStockException.class,
                refused -> assertThat(refused.available()).isEqualTo(99_000));
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
    @DisplayName("allocation: one whole carton pointing back at the reservation, which closes ALLOCATED")
    void allocation() {
        SkuReservation reservation = hold(4, 10);
        StockPackage item = stock(5);
        UUID request = UUID.randomUUID();

        Reservation allocation = item.allocate(reservation, request, NOW);
        reservation.markAllocated(NOW);

        assertThat(allocation.allocatedFrom()).isEqualTo(reservation.id().value());
        assertThat(allocation.orderId()).isEqualTo(reservation.orderId());
        assertThat(allocation.unitsPerCarton()).isEqualTo(PER_CARTON);
        assertThat(item.unitsHeld()).as("the carton is taken whole").isEqualTo(5);
        assertThat(item.allocate(reservation, request, NOW)).as("replayed").isSameAs(allocation);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.ALLOCATED);
        assertThatThrownBy(() -> item.allocate(hold(2, 10), UUID.randomUUID(), NOW))
                .as("a carton another order holds is not free").isInstanceOf(InsufficientStockException.class);
        assertThatThrownBy(() -> stock(5).allocate(SkuReservation.holdAt("HCM", new Sku("OTHER"), UUID.randomUUID(),
                UUID.randomUUID(), 1, PER_CARTON, 1, NOW), UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ADR-0011: a hold of cartons of 5 does not take a carton of another count, larger or smaller")
    void allocationWantsACartonOfTheHoldsCount() {
        SkuReservation reservation = hold(1, 10);

        assertThatThrownBy(() -> stock(10).allocate(reservation, UUID.randomUUID(), NOW))
                .as("a carton of 10 is not broken for a hold of cartons of 5")
                .isInstanceOfSatisfying(InsufficientStockException.class, refused -> {
                    assertThat(refused.requested()).isEqualTo(5);
                    assertThat(refused.available()).isZero();
                });
        assertThatThrownBy(() -> stock(3).allocate(reservation, UUID.randomUUID(), NOW))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(stock(5).allocate(reservation, UUID.randomUUID(), NOW).quantity()).isEqualTo(Quantity.of(5));
    }

    @Test
    @DisplayName("FIFO at allocation: the earliest real receipt time first, whole cartons (kltn-docs 01 BR-17)")
    void fifoAtAllocation() {
        LocationId bin = new LocationId("HCM-A01-1-A");
        var late = carton(bin, "L", "2026-01-01T00:00:00Z");
        var early = carton(bin, "L", "2024-01-01T00:00:00Z");
        var oldReceipt = carton(bin, null, "2025-01-01T00:00:00Z");
        var newReceipt = carton(bin, null, "2025-06-01T00:00:00Z");

        var plan = StockAllocator.plan("LY-GIAY-DEMO-12OZ-XANH", java.util.List.of(newReceipt, late, oldReceipt, early),
                4, 5);

        assertThat(plan.lines()).extracting(StockAllocator.AllocationLine::stockPackageId).containsExactly(
                early.stockPackageId(), oldReceipt.stockPackageId(), newReceipt.stockPackageId(), late.stockPackageId());
        assertThat(plan.isShort()).isFalse();
    }

    /** A sealed carton of 5. */
    private static StockAllocator.Candidate carton(LocationId bin, String lot, String received) {
        return new StockAllocator.Candidate(new StockPackageId(java.util.UUID.randomUUID()), bin, lot,
                StockStatus.AVAILABLE, 5, Instant.parse(received));
    }
}
