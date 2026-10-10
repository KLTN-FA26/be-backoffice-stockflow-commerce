package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the aggregate.
 *
 * <p>No Spring, no database, no {@code @SpringBootTest} — the whole class runs in milliseconds.
 * That is only possible because {@code StockItem} has no framework annotations on it, and it is
 * the practical payoff of that discipline: rules this important get tested exhaustively precisely
 * because testing them is cheap.</p>
 */
class StockItemTest {

    private static final Sku SKU = new Sku("SOFA-3S-GREY");
    private static final LocationId LOCATION = new LocationId("HCM-A-01-02-B");
    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");

    private static StockItem availableWith(int onHand) {
        StockItem item = new StockItem(StockItemId.newId(), SKU, LOCATION, null, null,
                Quantity.of(onHand), StockStatus.QUARANTINE, List.of(), 0L);
        item.releaseFromQuarantine();
        item.pullDomainEvents();
        return item;
    }

    @Nested
    @DisplayName("receiving goods")
    class Receiving {

        @Test
        @DisplayName("arrives in quarantine, not on sale")
        void receivedStockIsQuarantined() {
            StockItem item = StockItem.receive(SKU, LOCATION, "LOT-1", null, Quantity.of(10));

            assertThat(item.status()).isEqualTo(StockStatus.QUARANTINE);
            // The units are physically present but must not be sellable before QC.
            assertThat(item.onHand()).isEqualTo(Quantity.of(10));
            assertThat(item.available()).isEqualTo(Quantity.ZERO);
        }

        @Test
        @DisplayName("becomes sellable only after QC passes")
        void quarantineReleaseMakesStockAvailable() {
            StockItem item = StockItem.receive(SKU, LOCATION, "LOT-1", null, Quantity.of(10));

            item.releaseFromQuarantine();

            assertThat(item.available()).isEqualTo(Quantity.of(10));
        }
    }

    @Nested
    @DisplayName("reserving")
    class Reserving {

        @Test
        @DisplayName("lowers available but not on hand - nothing has physically moved")
        void reservationHoldsWithoutDeducting() {
            StockItem item = availableWith(10);

            item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(3), NOW);

            assertThat(item.onHand()).isEqualTo(Quantity.of(10));
            assertThat(item.reserved()).isEqualTo(Quantity.of(3));
            assertThat(item.available()).isEqualTo(Quantity.of(7));
        }

        @Test
        @DisplayName("refuses to oversell")
        void reservingMoreThanAvailableIsRejected() {
            StockItem item = availableWith(5);

            assertThatThrownBy(() ->
                    item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(6), NOW))
                    .isInstanceOf(InsufficientStockException.class)
                    .hasMessageContaining("requested 6")
                    .hasMessageContaining("available 5");
        }

        @Test
        @DisplayName("quarantined stock cannot be sold")
        void reservingQuarantinedStockIsRejected() {
            StockItem item = StockItem.receive(SKU, LOCATION, "LOT-1", null, Quantity.of(10));

            assertThatThrownBy(() ->
                    item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(1), NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("QUARANTINE");
        }

        @Test
        @DisplayName("the same request id reserves once, however many times it arrives")
        void reservationIsIdempotent() {
            StockItem item = availableWith(10);
            UUID requestId = UUID.randomUUID();
            UUID orderId = UUID.randomUUID();

            Reservation first = item.reserve(orderId, requestId, requestId, Quantity.of(3), NOW);
            Reservation second = item.reserve(orderId, requestId, requestId, Quantity.of(3), NOW);

            // A double-clicked checkout button must not hold six units.
            assertThat(second.id()).isEqualTo(first.id());
            assertThat(item.reserved()).isEqualTo(Quantity.of(3));
        }

        @Test
        @DisplayName("holds for one caller request are findable before any replanning")
        void activeReservationsForFindsTheCallersHolds() {
            StockItem item = availableWith(10);
            UUID root = UUID.randomUUID();
            item.reserve(UUID.randomUUID(), root, UUID.randomUUID(), Quantity.of(3), NOW);

            // The replay check reads this, and it must run before availability is recomputed:
            // the hold above has already changed what the planner would see.
            assertThat(item.activeReservationsFor(root)).hasSize(1);
            assertThat(item.activeReservationsFor(UUID.randomUUID())).isEmpty();
        }

        @Test
        @DisplayName("announces StockReserved so other modules can react")
        void reservationRegistersAnEvent() {
            StockItem item = availableWith(10);

            item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(2), NOW);

            assertThat(item.pullDomainEvents())
                    .singleElement()
                    .isInstanceOf(StockEvent.Reserved.class);
        }
    }

    @Nested
    @DisplayName("releasing")
    class Releasing {

        @Test
        @DisplayName("puts the units back on sale")
        void releaseRestoresAvailability() {
            StockItem item = availableWith(10);
            Reservation reservation =
                    item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(4), NOW);

            item.releaseReservation(reservation.id(), ReleaseReason.ORDER_CANCELLED, NOW);

            assertThat(item.available()).isEqualTo(Quantity.of(10));
            assertThat(reservation.status()).isEqualTo(ReservationStatus.RELEASED);
        }

        @Test
        @DisplayName("releasing twice is harmless")
        void releaseIsIdempotent() {
            StockItem item = availableWith(10);
            Reservation reservation =
                    item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(4), NOW);

            item.releaseReservation(reservation.id(), ReleaseReason.ORDER_CANCELLED, NOW);
            // The cancel path and the expiry sweeper genuinely race here; neither may fail because
            // the other won.
            item.releaseReservation(reservation.id(), ReleaseReason.RESERVATION_EXPIRED, NOW);

            assertThat(item.available()).isEqualTo(Quantity.of(10));
            assertThat(reservation.releaseReason()).isEqualTo(ReleaseReason.ORDER_CANCELLED);
        }

        @Test
        @DisplayName("a released hold does not make the request look already-served")
        void releasedHoldsDoNotCountAsIdempotencyHits() {
            StockItem item = availableWith(10);
            UUID requestId = UUID.randomUUID();
            Reservation first = item.reserve(UUID.randomUUID(), requestId, requestId,
                    Quantity.of(3), NOW);
            item.releaseReservation(first.id(), ReleaseReason.ORDER_CANCELLED, NOW);

            // The row survives as an audit trail. Matching on the id alone would hand the caller
            // back a reservation that holds nothing and releases nothing.
            assertThat(item.findByRequestId(requestId)).isEmpty();

            Reservation second = item.reserve(UUID.randomUUID(), requestId, requestId,
                    Quantity.of(3), NOW);
            assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(item.reserved()).isEqualTo(Quantity.of(3));
        }

        @Test
        @DisplayName("expired holds are swept, live ones are not")
        void sweepReleasesOnlyExpiredHolds() {
            StockItem item = availableWith(10);
            item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    Quantity.of(3), NOW, Duration.ofMinutes(30));
            item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    Quantity.of(2), NOW, Duration.ofHours(4));

            int released = item.releaseExpired(NOW.plus(Duration.ofMinutes(31)));

            assertThat(released).isEqualTo(1);
            assertThat(item.reserved()).isEqualTo(Quantity.of(2));
        }
    }

    @Nested
    @DisplayName("deducting")
    class Deducting {

        @Test
        @DisplayName("only a live hold can become a real deduction")
        void consumingAReservationLowersOnHand() {
            StockItem item = availableWith(10);
            Reservation reservation =
                    item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(4), NOW);

            item.consumeReservation(reservation.id(), NOW);

            assertThat(item.onHand()).isEqualTo(Quantity.of(6));
            assertThat(item.reserved()).isEqualTo(Quantity.ZERO);
            assertThat(item.available()).isEqualTo(Quantity.of(6));
        }

        @Test
        @DisplayName("a released hold cannot then be shipped")
        void consumingAReleasedReservationIsRejected() {
            StockItem item = availableWith(10);
            Reservation reservation =
                    item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(4), NOW);
            item.releaseReservation(reservation.id(), ReleaseReason.ORDER_CANCELLED, NOW);

            assertThatThrownBy(() -> item.consumeReservation(reservation.id(), NOW))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already RELEASED");
        }
    }

    @Nested
    @DisplayName("invariants")
    class Invariants {

        @Test
        @DisplayName("a stock count below what is reserved is refused, not silently applied")
        void adjustingBelowReservedIsRejected() {
            StockItem item = availableWith(10);
            item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(6), NOW);

            // Shrinkage found by a physical count. Quietly dropping customers' holds to make the
            // numbers add up would be far worse than failing loudly.
            assertThatThrownBy(() -> item.adjustTo(Quantity.of(4)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("release the affected reservations");
        }

        @Test
        @DisplayName("stock with live holds cannot be blocked out from under them")
        void blockingReservedStockIsRejected() {
            StockItem item = availableWith(10);
            item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(2), NOW);

            assertThatThrownBy(() -> item.block(StockStatus.DAMAGED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("still reserved");
        }

        @Test
        @DisplayName("a corrupted row cannot be rehydrated into a valid aggregate")
        void constructingWithReservedAboveOnHandIsRejected() {
            Reservation tooBig = new Reservation(ReservationId.newId(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), Quantity.of(20), NOW,
                    NOW.plusSeconds(600), ReservationStatus.HELD, null, null);

            assertThatThrownBy(() -> new StockItem(StockItemId.newId(), SKU, LOCATION, null, null,
                    Quantity.of(5), StockStatus.AVAILABLE, List.of(tooBig), 0L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Invariant violated");
        }
    }

    // ---- SCRUM-465: a hold expires only while its order waits for payment (kltn-docs 14 BR-07) ----

    @Test
    @DisplayName("pinning an order's holds stops them expiring; other orders' holds still expire")
    void pinnedHoldsDoNotExpire() {
        StockItem item = availableWith(10);
        UUID paid = UUID.randomUUID();
        UUID unpaid = UUID.randomUUID();
        Reservation kept = item.reserve(paid, UUID.randomUUID(), UUID.randomUUID(), Quantity.of(3), NOW);
        item.reserve(unpaid, UUID.randomUUID(), UUID.randomUUID(), Quantity.of(2), NOW);

        assertThat(item.pinReservationsOf(paid)).isEqualTo(1);
        assertThat(item.pinReservationsOf(paid)).isZero();   // idempotent
        assertThat(kept.isPinned()).isTrue();
        assertThat(kept.expiresAt()).isNull();

        int released = item.releaseExpired(NOW.plus(java.time.Duration.ofDays(30)));

        assertThat(released).isEqualTo(1);
        assertThat(kept.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(item.reserved()).isEqualTo(Quantity.of(3));
    }

    @Test
    @DisplayName("SCRUM-427: an order waiting for payment again gets a deadline on its pinned holds")
    void rearmedHoldsExpireAgain() {
        StockItem item = availableWith(10);
        UUID orderId = UUID.randomUUID();
        Reservation hold = item.reserve(orderId, UUID.randomUUID(), UUID.randomUUID(), Quantity.of(3), NOW);
        item.pinReservationsOf(orderId);
        java.time.Instant deadline = NOW.plus(java.time.Duration.ofMinutes(30));

        assertThat(item.rearmReservationsOf(orderId, deadline)).isEqualTo(1);
        assertThat(item.rearmReservationsOf(orderId, deadline.plusSeconds(60))).isZero();   // idempotent
        assertThat(hold.expiresAt()).isEqualTo(deadline);
        assertThat(item.releaseExpired(deadline)).isEqualTo(1);
        assertThat(hold.status()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    @DisplayName("a closed hold is not pinned, and a pinned hold can still be released or consumed")
    void pinOnlyTouchesActiveHolds() {
        StockItem item = availableWith(10);
        UUID orderId = UUID.randomUUID();
        Reservation cancelled = item.reserve(orderId, UUID.randomUUID(), UUID.randomUUID(), Quantity.of(2), NOW);
        item.releaseReservation(cancelled.id(), ReleaseReason.ORDER_CANCELLED, NOW);
        Reservation live = item.reserve(orderId, UUID.randomUUID(), UUID.randomUUID(), Quantity.of(3), NOW);

        assertThat(item.pinReservationsOf(orderId)).isEqualTo(1);
        assertThat(cancelled.expiresAt()).isNotNull();

        item.releaseReservation(live.id(), ReleaseReason.ORDER_CANCELLED, NOW);
        assertThat(live.status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(item.reserved()).isEqualTo(Quantity.ZERO);
    }
}
