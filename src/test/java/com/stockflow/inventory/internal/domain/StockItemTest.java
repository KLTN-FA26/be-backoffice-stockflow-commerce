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
        StockItem item = new StockItem(StockItemId.newId(), SKU, LOCATION, null, Quantity.of(onHand), StockStatus.QUARANTINE, List.of(), 0L);
        item.releaseFromQuarantine();
        item.pullDomainEvents();
        return item;
    }

    @Nested
    @DisplayName("the row of a carton found again (4C-3, L4)")
    class ComingBack {

        private StockItem emptyCartonRow(StockStatus status) {
            return new StockItem(StockItemId.newId(), SKU, LOCATION, "L1", Quantity.ZERO, status, List.of(), 0L,
                    NOW, null, new StockPackageId(UUID.randomUUID()));
        }

        @Test
        @DisplayName("an empty carton row comes back AVAILABLE or QUARANTINE, whatever it was")
        void comesBackAsAvailableOrQuarantine() {
            for (StockStatus before : StockStatus.values()) {
                StockItem row = emptyCartonRow(before);
                row.comeBackAs(StockStatus.AVAILABLE);
                assertThat(row.status()).as("from " + before).isEqualTo(StockStatus.AVAILABLE);
                row.comeBackAs(StockStatus.QUARANTINE);
                assertThat(row.status()).isEqualTo(StockStatus.QUARANTINE);
            }
        }

        @Test
        @DisplayName("no other status: a carton found is not found DAMAGED, BLOCKED, INBOUND, EXPIRED or in production")
        void onlyAvailableOrQuarantine() {
            for (StockStatus target : List.of(StockStatus.INBOUND, StockStatus.BLOCKED, StockStatus.DAMAGED,
                    StockStatus.EXPIRED, StockStatus.IN_PRODUCTION)) {
                assertThatThrownBy(() -> emptyCartonRow(StockStatus.AVAILABLE).comeBackAs(target))
                        .as(target.name()).isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("only the empty row of a carton: not one holding units, not a loose row")
        void onlyAnEmptyCartonRow() {
            StockItem holding = new StockItem(StockItemId.newId(), SKU, LOCATION, "L1", Quantity.of(3),
                    StockStatus.AVAILABLE, List.of(), 0L, NOW, null, new StockPackageId(UUID.randomUUID()));
            StockItem loose = new StockItem(StockItemId.newId(), SKU, LOCATION, "L1", Quantity.ZERO,
                    StockStatus.AVAILABLE, List.of(), 0L, NOW, null, (StockPackageId) null);

            assertThatThrownBy(() -> holding.comeBackAs(StockStatus.AVAILABLE))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> loose.comeBackAs(StockStatus.AVAILABLE))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("receiving goods")
    class Receiving {

        @Test
        @DisplayName("arrives in quarantine, not on sale")
        void receivedStockIsQuarantined() {
            StockItem item = StockItem.receive(SKU, LOCATION, "LOT-1", Quantity.of(10));

            assertThat(item.status()).isEqualTo(StockStatus.QUARANTINE);
            // The units are physically present but must not be sellable before QC.
            assertThat(item.onHand()).isEqualTo(Quantity.of(10));
            assertThat(item.available()).isEqualTo(Quantity.ZERO);
        }

        @Test
        @DisplayName("becomes sellable only after QC passes")
        void quarantineReleaseMakesStockAvailable() {
            StockItem item = StockItem.receive(SKU, LOCATION, "LOT-1", Quantity.of(10));

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
            StockItem item = StockItem.receive(SKU, LOCATION, "LOT-1", Quantity.of(10));

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

            // SCRUM-267: a business refusal (409 RESERVATION_NOT_ACTIVE), no longer an IllegalStateException
            // that the exception handler turned into a 400.
            assertThatThrownBy(() -> item.consumeReservation(reservation.id(), NOW))
                    .isInstanceOfSatisfying(com.stockflow.common.error.BusinessException.class, refusal ->
                            assertThat(refusal.errorCode())
                                    .isEqualTo(com.stockflow.common.error.ErrorCode.RESERVATION_NOT_ACTIVE))
                    .hasMessageContaining("already RELEASED");
            assertThat(item.onHand()).isEqualTo(Quantity.of(10));
        }

        @Test
        @DisplayName("a hold consumed once cannot be consumed again")
        void consumingTwiceIsRejected() {
            StockItem item = availableWith(10);
            Reservation reservation =
                    item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(4), NOW);
            item.consumeReservation(reservation.id(), NOW);

            assertThatThrownBy(() -> item.consumeReservation(reservation.id(), NOW))
                    .isInstanceOfSatisfying(com.stockflow.common.error.BusinessException.class, refusal ->
                            assertThat(refusal.errorCode())
                                    .isEqualTo(com.stockflow.common.error.ErrorCode.RESERVATION_NOT_ACTIVE));
            assertThat(item.onHand()).isEqualTo(Quantity.of(6));
        }

        @Test
        @DisplayName("a hold past its payment deadline is not consumed, even before the sweeper closes it")
        void consumingAHoldPastItsDeadlineIsRejected() {
            StockItem item = availableWith(10);
            Reservation reservation = item.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    Quantity.of(4), NOW, Duration.ofMinutes(30));

            assertThatThrownBy(() -> item.consumeReservation(reservation.id(), NOW.plus(Duration.ofMinutes(31))))
                    .isInstanceOfSatisfying(com.stockflow.common.error.BusinessException.class, refusal ->
                            assertThat(refusal.errorCode())
                                    .isEqualTo(com.stockflow.common.error.ErrorCode.RESERVATION_NOT_ACTIVE));
            assertThat(item.onHand()).isEqualTo(Quantity.of(10));
        }

        @Test
        @DisplayName("a hold of another stock item is not found here")
        void consumingAnUnknownHoldIsNotFound() {
            StockItem item = availableWith(10);

            assertThatThrownBy(() -> item.consumeReservation(ReservationId.newId(), NOW))
                    .isInstanceOfSatisfying(com.stockflow.common.error.BusinessException.class, refusal ->
                            assertThat(refusal.errorCode())
                                    .isEqualTo(com.stockflow.common.error.ErrorCode.RESERVATION_NOT_FOUND));
        }

        @Test
        @DisplayName("put-back: picked goods come back onto sellable stock only")
        void putBackAddsToAvailableStockOnly() {
            StockItem item = availableWith(10);
            item.putBack(Quantity.of(3));
            assertThat(item.onHand()).isEqualTo(Quantity.of(13));

            StockItem quarantined = new StockItem(StockItemId.newId(), SKU, LOCATION, null, Quantity.of(5), StockStatus.QUARANTINE, List.of(), 0L);
            assertThatThrownBy(() -> quarantined.putBack(Quantity.of(1)))
                    .isInstanceOfSatisfying(com.stockflow.common.error.BusinessException.class, refusal ->
                            assertThat(refusal.errorCode())
                                    .isEqualTo(com.stockflow.common.error.ErrorCode.STOCK_STATUS_MISMATCH));
            assertThat(quarantined.onHand()).isEqualTo(Quantity.of(5));
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
        @DisplayName("a corrupted row cannot be rehydrated into a valid aggregate")
        void constructingWithReservedAboveOnHandIsRejected() {
            Reservation tooBig = new Reservation(ReservationId.newId(), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), Quantity.of(20), NOW,
                    NOW.plusSeconds(600), ReservationStatus.HELD, null, null);

            assertThatThrownBy(() -> new StockItem(StockItemId.newId(), SKU, LOCATION, null, Quantity.of(5), StockStatus.AVAILABLE, List.of(tooBig), 0L))
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

    @Nested
    @DisplayName("rows in a carton")
    class InCartons {

        private final StockPackageId carton = new StockPackageId(UUID.randomUUID());
        private final LocationId destination = new LocationId("HCM-QC01");

        private StockItem cartonRow(StockStatus status, List<Reservation> holds) {
            return new StockItem(StockItemId.newId(), SKU, LOCATION, "LOT-1", Quantity.of(10), status, holds, 0L,
                    NOW, UUID.randomUUID(), carton);
        }

        @Test
        @DisplayName("received into a carton: INBOUND, at the receiving location, carrying its carton")
        void receiveInboundIntoKeepsTheCarton() {
            UUID lot = UUID.randomUUID();

            StockItem item = StockItem.receiveInboundInto(carton, SKU, LOCATION, "LOT-1", Quantity.of(980),
                    NOW, lot);

            assertThat(item.stockPackageId()).isEqualTo(carton);
            assertThat(item.isLoose()).isFalse();
            assertThat(item.status()).isEqualTo(StockStatus.INBOUND);
            assertThat(item.location()).isEqualTo(LOCATION);
            assertThat(item.lotId()).isEqualTo(lot);
            assertThat(item.onHand()).isEqualTo(Quantity.of(980));
        }

        @Test
        @DisplayName("a carton row follows its carton in place; a loose row never does")
        void followPackageMovesOnlyCartonRows() {
            StockItem row = cartonRow(StockStatus.INBOUND, List.of());
            StockItemId id = row.id();

            row.followPackageTo(destination);

            assertThat(row.location()).isEqualTo(destination);
            assertThat(row.id()).isEqualTo(id);
            assertThat(row.status()).isEqualTo(StockStatus.INBOUND);
            assertThat(row.stockPackageId()).isEqualTo(carton);
            assertThatThrownBy(() -> availableWith(5).followPackageTo(destination))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("an INBOUND carton row can be put in quarantine or blocked, in place")
        void reclassifyInPackageQuarantinesInbound() {
            StockItem row = cartonRow(StockStatus.INBOUND, List.of());

            row.reclassifyInPackage(StockStatus.QUARANTINE);
            assertThat(row.status()).isEqualTo(StockStatus.QUARANTINE);

            row.reclassifyInPackage(StockStatus.BLOCKED);
            assertThat(row.status()).isEqualTo(StockStatus.BLOCKED);
            assertThatThrownBy(() -> row.reclassifyInPackage(StockStatus.QUARANTINE))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> cartonRow(StockStatus.INBOUND, List.of())
                    .reclassifyInPackage(StockStatus.IN_PRODUCTION)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("quarantineInPackage (4C-6, a damaged carton back from the check station): AVAILABLE or INBOUND goes on "
                + "hold in place, keeping its carton and its units, and stops being reservable")
        void quarantineInPackageHoldsTheCartonInPlace() {
            for (StockStatus before : List.of(StockStatus.AVAILABLE, StockStatus.INBOUND)) {
                StockItem row = cartonRow(before, List.of());
                StockItemId id = row.id();

                row.quarantineInPackage();

                assertThat(row.status()).isEqualTo(StockStatus.QUARANTINE);
                assertThat(row.id()).isEqualTo(id);
                assertThat(row.stockPackageId()).isEqualTo(carton);
                assertThat(row.onHand()).isEqualTo(Quantity.of(10));
                assertThat(row.status().isReservable()).isFalse();
            }
        }

        @Test
        @DisplayName("quarantineInPackage refuses a loose row, a row not AVAILABLE or INBOUND, and units held for an order")
        void quarantineInPackageRefusals() {
            assertThatThrownBy(() -> availableWith(5).quarantineInPackage()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("loose");
            for (StockStatus status : List.of(StockStatus.QUARANTINE, StockStatus.BLOCKED, StockStatus.DAMAGED)) {
                StockItem row = cartonRow(status, List.of());
                assertThatThrownBy(row::quarantineInPackage).as("%s", status).isInstanceOf(IllegalStateException.class);
                assertThat(row.status()).isEqualTo(status);
            }
            StockItem held = cartonRow(StockStatus.AVAILABLE, List.of());
            held.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(2), NOW);
            assertThatThrownBy(held::quarantineInPackage).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("held for an order");
            assertThat(held.status()).isEqualTo(StockStatus.AVAILABLE);
        }

        @Test
        @DisplayName("putaway: an INBOUND carton row becomes AVAILABLE in place")
        void inboundRowInCartonBecomesAvailable() {
            StockItem row = cartonRow(StockStatus.INBOUND, List.of());
            StockItemId id = row.id();

            row.reclassifyInPackage(StockStatus.AVAILABLE);

            assertThat(row.status()).isEqualTo(StockStatus.AVAILABLE);
            assertThat(row.id()).isEqualTo(id);
            assertThat(row.stockPackageId()).isNotNull();
        }

        @Test
        @DisplayName("a released quarantined carton row becomes AVAILABLE when put away (05 §4.4, team plan 4D-1)")
        void quarantinedRowInCartonIsPutAwayAsAvailable() {
            StockItem row = cartonRow(StockStatus.QUARANTINE, List.of());

            row.reclassifyInPackage(StockStatus.AVAILABLE);

            assertThat(row.status()).isEqualTo(StockStatus.AVAILABLE);
        }

        @Test
        @DisplayName("a putaway takes only the status its task puts away: INBOUND for a receipt, QUARANTINE for a release")
        void putAwayInPackageTakesItsOwnSource() {
            StockItem inbound = cartonRow(StockStatus.INBOUND, List.of());
            assertThatThrownBy(() -> inbound.putAwayInPackage(StockStatus.QUARANTINE))
                    .isInstanceOf(IllegalStateException.class);
            inbound.putAwayInPackage(StockStatus.INBOUND);
            assertThat(inbound.status()).isEqualTo(StockStatus.AVAILABLE);

            StockItem quarantined = cartonRow(StockStatus.QUARANTINE, List.of());
            assertThatThrownBy(() -> quarantined.putAwayInPackage(StockStatus.INBOUND))
                    .isInstanceOf(IllegalStateException.class);
            quarantined.putAwayInPackage(StockStatus.QUARANTINE);
            assertThat(quarantined.status()).isEqualTo(StockStatus.AVAILABLE);
            assertThatThrownBy(() -> cartonRow(StockStatus.AVAILABLE, List.of()).putAwayInPackage(StockStatus.AVAILABLE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a free AVAILABLE or INBOUND carton row is quarantined in place; nothing else is")
        void quarantineInPackage() {
            StockItem available = cartonRow(StockStatus.AVAILABLE, List.of());
            StockItemId id = available.id();
            available.quarantineInPackage();
            assertThat(available.status()).isEqualTo(StockStatus.QUARANTINE);
            assertThat(available.id()).isEqualTo(id);
            assertThat(available.stockPackageId()).isEqualTo(carton);

            StockItem inbound = cartonRow(StockStatus.INBOUND, List.of());
            inbound.quarantineInPackage();
            assertThat(inbound.status()).isEqualTo(StockStatus.QUARANTINE);

            assertThatThrownBy(available::quarantineInPackage).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> cartonRow(StockStatus.BLOCKED, List.of()).quarantineInPackage())
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> availableWith(5).quarantineInPackage())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a carton row held for an order is not quarantined under its hold (20 BR-21)")
        void quarantineRefusesAHeldRow() {
            Reservation hold = new Reservation(new ReservationId(UUID.randomUUID()), UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), Quantity.of(3), NOW, NOW.plus(Duration.ofMinutes(30)),
                    ReservationStatus.HELD, null, null);
            StockItem row = cartonRow(StockStatus.AVAILABLE, List.of(hold));

            assertThatThrownBy(row::quarantineInPackage).isInstanceOf(IllegalStateException.class);
            assertThat(row.status()).isEqualTo(StockStatus.AVAILABLE);
        }

        @Test
        @DisplayName("a row with live holds is not reclassified under them")
        void reclassifyInPackageRefusesReservedUnits() {
            UUID order = UUID.randomUUID();
            Reservation hold = new Reservation(new ReservationId(UUID.randomUUID()), order, UUID.randomUUID(),
                    UUID.randomUUID(), Quantity.of(3), NOW, NOW.plus(Duration.ofMinutes(30)), ReservationStatus.HELD,
                    null, null);
            StockItem row = cartonRow(StockStatus.QUARANTINE, List.of(hold));

            assertThatThrownBy(() -> row.reclassifyInPackage(StockStatus.BLOCKED))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(row.status()).isEqualTo(StockStatus.QUARANTINE);
        }
    }
}
