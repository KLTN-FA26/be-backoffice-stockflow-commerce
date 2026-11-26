package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.DomainEvent;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.StockAllocated;
import com.stockflow.contracts.StockDeducted;
import com.stockflow.contracts.StockHeldCartonWrittenOff;
import com.stockflow.contracts.StockReservationReleased;
import com.stockflow.contracts.StockWrittenOffByCount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The carton, which is the stock (ADR-0011, V20261231000600): its life as the checks and triggers of the table state
 * it a second time, and the rules the stock row beside it used to carry - one live allocation, only free AVAILABLE
 * stock allocated, a held carton never changed from under its hold, lot and FIFO date kept for good.
 *
 * <p>No Spring, no database: the aggregate knows neither, so every rule runs in milliseconds.</p>
 */
class StockPackageTest {

    private static final Instant NOW = Instant.parse("2026-10-21T08:00:00Z");
    private static final Instant RECEIVED = Instant.parse("2026-10-20T02:00:00Z");
    private static final Sku SKU = new Sku("CUP-PP-500");
    private static final LocationId RECEIVING = new LocationId("HCM-RCV01");
    private static final LocationId BIN = new LocationId("HCM-A01-1-A");
    private static final UUID LOT = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID SKU_HOLD = UUID.randomUUID();
    private static final UUID DECIDER = UUID.randomUUID();

    private static StockPackage labelled() {
        return StockPackage.receiving(new StockPackageId(UUID.randomUUID()), "LPN-20261021-000001", SKU,
                UUID.randomUUID(), UUID.randomUUID(), 1000, RECEIVING, UUID.randomUUID());
    }

    /** Received: IN_STOCK, INBOUND, in its lot, at the receiving area. */
    private static StockPackage inStock() {
        StockPackage carton = labelled();
        carton.count(NOW);
        carton.putInStock(LOT, "LOT-1", RECEIVED);
        return carton;
    }

    /** Put away: AVAILABLE on a bin. */
    private static StockPackage available() {
        StockPackage carton = inStock();
        carton.moveTo(BIN);
        carton.putAway(StockStatus.INBOUND);
        return carton;
    }

    private static Reservation allocate(StockPackage carton) {
        return carton.allocate(ORDER, SKU_HOLD, SKU_HOLD, UUID.randomUUID(), 1000, NOW);
    }

    private static StockPackage held() {
        StockPackage carton = available();
        allocate(carton);
        carton.pullDomainEvents();
        return carton;
    }

    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION));
    }

    private static ErrorCode codeOf(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        try {
            call.call();
        } catch (BusinessException e) {
            return e.errorCode();
        } catch (Throwable other) {
            throw new AssertionError("Expected a BusinessException, got " + other, other);
        }
        throw new AssertionError("Expected a BusinessException, nothing was thrown");
    }

    private static <T> T payload(StockPackage carton, Class<T> type) {
        List<DomainEvent> events = carton.pullDomainEvents();
        return events.stream().map(e -> ((StockEvent) e).payload()).filter(type::isInstance).map(type::cast)
                .findFirst().orElseThrow(() -> new AssertionError("No " + type.getSimpleName() + " among " + events));
    }

    // ------------------------------------------------------------------ its life

    @Test
    @DisplayName("labelled RECEIVING, nominal = the unit's factor, not yet counted, no stock status, no lot")
    void labelledOnAReceipt() {
        StockPackage carton = labelled();
        assertThat(carton.status()).isEqualTo(StockPackageStatus.RECEIVING);
        assertThat(carton.nominalQty()).isEqualTo(1000);
        assertThat(carton.sku()).isEqualTo(SKU);
        assertThat(carton.countedAt()).isNull();
        assertThat(carton.counted()).isFalse();
        assertThat(carton.stockStatus()).isNull();
        assertThat(carton.lotId()).isNull();
        assertThat(carton.receivedAt()).isNull();
        assertThat(carton.unitsInStock()).as("a carton being received is not stock yet").isZero();
    }

    @Test
    @DisplayName("counted while receiving - the moment, a recount moving it (03 BR-13) - then fixed in stock")
    void countedThenFixed() {
        StockPackage carton = labelled();
        refused(() -> carton.putInStock(LOT, "LOT-1", RECEIVED));
        carton.count(NOW);
        assertThat(carton.countedAt()).isEqualTo(NOW);
        carton.count(NOW.plusSeconds(60));
        carton.putInStock(LOT, "LOT-1", RECEIVED);
        assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
        assertThat(carton.countedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(carton.counted()).isTrue();
        refused(() -> carton.count(NOW.plusSeconds(120)));
        assertThat(carton.countedAt()).as("fixed once in stock").isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    @DisplayName("into stock: INBOUND, never sellable yet, in the receipt's lot with the receipt's time as its FIFO date")
    void putInStockIsInboundInItsLot() {
        StockPackage carton = inStock();

        assertThat(carton.stockStatus()).isEqualTo(StockStatus.INBOUND);
        assertThat(carton.inStock()).isTrue();
        assertThat(carton.lotId()).isEqualTo(LOT);
        assertThat(carton.lotNumber()).isEqualTo("LOT-1");
        assertThat(carton.receivedAt()).isEqualTo(RECEIVED);
        assertThat(carton.location()).isEqualTo(RECEIVING);
        assertThat(carton.unitsInStock()).isEqualTo(1000);
        assertThat(carton.unitsAvailable()).as("received, not put away: on hand, not promised").isZero();
        assertThat(carton.isFree()).isFalse();
    }

    @Test
    @DisplayName("every unit carries a lot (20 BR-22): a carton goes into stock with a lot, its number and a receipt time")
    void putInStockNeedsItsLot() {
        StockPackage counted = labelled();
        counted.count(NOW);
        assertThatThrownBy(() -> counted.putInStock(null, "LOT-1", RECEIVED)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> counted.putInStock(LOT, " ", RECEIVED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> counted.putInStock(LOT, "LOT-1", null)).isInstanceOf(NullPointerException.class);
        refused(() -> inStock().putInStock(LOT, "LOT-1", RECEIVED));
    }

    @Test
    @DisplayName("a receipt cancelled before posting voids its cartons; nothing else does")
    void voided() {
        StockPackage carton = labelled();
        carton.cancel(NOW);
        assertThat(carton.status()).isEqualTo(StockPackageStatus.VOID);
        assertThat(carton.closedAt()).isEqualTo(NOW);
        refused(() -> inStock().cancel(NOW));
    }

    @Test
    @DisplayName("it leaves stock whole by a decision: CONSUMED, closed, no stock status; a consumed carton is not moved")
    void consumedWhole() {
        StockPackage carton = inStock();
        carton.consume(NOW);
        assertThat(carton.status()).isEqualTo(StockPackageStatus.CONSUMED);
        assertThat(carton.stockStatus()).as("a carton out of stock has no stock status").isNull();
        assertThat(carton.closedAt()).isEqualTo(NOW);
        assertThat(carton.unitsInStock()).isZero();
        refused(() -> carton.consume(NOW));
        refused(() -> carton.moveTo(BIN));
        refused(() -> labelled().consume(NOW));
    }

    @Test
    @DisplayName("CONSUMED -> IN_STOCK: back whole, where and as what the operation says, its lot and FIFO date kept")
    void restockedWhole() {
        StockPackage carton = available();
        carton.consume(NOW);
        LocationId bin = new LocationId("HCM-A02-1-A");

        carton.restock(bin, StockStatus.AVAILABLE);

        assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
        assertThat(carton.stockStatus()).isEqualTo(StockStatus.AVAILABLE);
        assertThat(carton.closedAt()).isNull();
        assertThat(carton.location()).isEqualTo(bin);
        assertThat(carton.countedAt()).as("the count it was received with stands").isEqualTo(NOW);
        assertThat(carton.lotId()).as("the same goods").isEqualTo(LOT);
        assertThat(carton.receivedAt()).as("as old as they were: FIFO keeps their age").isEqualTo(RECEIVED);
        refused(() -> carton.restock(bin, StockStatus.AVAILABLE));
        refused(() -> labelled().restock(bin, StockStatus.AVAILABLE));
        StockPackage lost = inStock();
        lost.lose(NOW);
        refused(() -> lost.restock(bin, StockStatus.AVAILABLE));
    }

    @Test
    @DisplayName("a carton removed as damaged or returned by a customer comes back QUARANTINE: out of ATP")
    void restockedIntoQuarantine() {
        StockPackage carton = available();
        carton.consume(NOW);

        carton.restock(new LocationId("HCM-QC01"), StockStatus.QUARANTINE);

        assertThat(carton.stockStatus()).isEqualTo(StockStatus.QUARANTINE);
        assertThat(carton.unitsInStock()).isEqualTo(1000);
        assertThat(carton.unitsAvailable()).isZero();
        assertThat(carton.isFree()).isFalse();
    }

    @Test
    @DisplayName("a carton comes back AVAILABLE or QUARANTINE, never DAMAGED, BLOCKED, INBOUND, EXPIRED or in production")
    void comesBackOnlyAvailableOrQuarantine() {
        for (StockStatus status : new StockStatus[] {StockStatus.DAMAGED, StockStatus.BLOCKED, StockStatus.INBOUND,
                StockStatus.EXPIRED, StockStatus.IN_PRODUCTION}) {
            StockPackage consumed = available();
            consumed.consume(NOW);
            assertThatThrownBy(() -> consumed.restock(BIN, status)).as("restocked %s", status)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(consumed.status()).as("refused before anything changed").isEqualTo(StockPackageStatus.CONSUMED);
            StockPackage lost = available();
            lost.lose(NOW);
            assertThatThrownBy(() -> lost.found(status)).as("found %s", status)
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(lost.status()).isEqualTo(StockPackageStatus.LOST);
        }
    }

    @Test
    @DisplayName("LOST and closed; found again it is back in stock whole where it stands, as the status given (4C-3, L4)")
    void lostAndFound() {
        StockPackage carton = available();
        carton.lose(NOW);
        assertThat(carton.status()).isEqualTo(StockPackageStatus.LOST);
        assertThat(carton.stockStatus()).isNull();
        assertThat(carton.closedAt()).isEqualTo(NOW);

        carton.found(StockStatus.AVAILABLE);

        assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
        assertThat(carton.stockStatus()).isEqualTo(StockStatus.AVAILABLE);
        assertThat(carton.closedAt()).isNull();
        assertThat(carton.location()).isEqualTo(BIN);
        assertThat(carton.lotId()).isEqualTo(LOT);
        assertThat(carton.receivedAt()).isEqualTo(RECEIVED);
        assertThat(carton.isFree()).as("found on the shelf: the allocatable carton it was").isTrue();
        refused(() -> carton.found(StockStatus.AVAILABLE));

        StockPackage inQuarantine = available();
        inQuarantine.lose(NOW);
        inQuarantine.found(StockStatus.QUARANTINE);
        assertThat(inQuarantine.stockStatus()).as("found in a quarantine area: never sold by being found")
                .isEqualTo(StockStatus.QUARANTINE);
    }

    @Test
    @DisplayName("moved whole while it is live; its allocation goes with it (staging)")
    void moved() {
        StockPackage carton = held();
        LocationId staging = new LocationId("HCM-STG01");

        carton.moveTo(staging);

        assertThat(carton.location()).isEqualTo(staging);
        assertThat(carton.isHeld()).as("a staged carton keeps its allocation").isTrue();
        assertThat(carton.stockStatus()).isEqualTo(StockStatus.AVAILABLE);
    }

    // ------------------------------------------------------------------ stock status

    @Nested
    @DisplayName("stock status, in place")
    class StockStatusInPlace {

        @Test
        @DisplayName("an INBOUND carton can be put in quarantine or blocked, in place")
        void reclassifyQuarantinesInbound() {
            StockPackage carton = inStock();
            carton.reclassify(StockStatus.QUARANTINE);
            assertThat(carton.stockStatus()).isEqualTo(StockStatus.QUARANTINE);
            assertThat(carton.location()).isEqualTo(RECEIVING);

            StockPackage blocked = inStock();
            blocked.reclassify(StockStatus.BLOCKED);
            assertThat(blocked.stockStatus()).isEqualTo(StockStatus.BLOCKED);

            assertThatThrownBy(() -> inStock().reclassify(StockStatus.DAMAGED))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> available().reclassify(StockStatus.QUARANTINE))
                    .as("only INBOUND or QUARANTINE stock takes a receiving decision")
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> labelled().reclassify(StockStatus.QUARANTINE))
                    .as("not in stock").isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("putaway: an INBOUND carton becomes AVAILABLE in place")
        void inboundCartonBecomesAvailable() {
            StockPackage carton = inStock();

            carton.putAway(StockStatus.INBOUND);

            assertThat(carton.stockStatus()).isEqualTo(StockStatus.AVAILABLE);
            assertThat(carton.isFree()).isTrue();
            assertThat(carton.unitsAvailable()).isEqualTo(1000);
        }

        @Test
        @DisplayName("a released quarantined carton becomes AVAILABLE when put away (05 §4.4, team plan 4D-1)")
        void quarantinedCartonIsPutAwayAsAvailable() {
            StockPackage carton = inStock();
            carton.reclassify(StockStatus.QUARANTINE);

            carton.putAway(StockStatus.QUARANTINE);

            assertThat(carton.stockStatus()).isEqualTo(StockStatus.AVAILABLE);
        }

        @Test
        @DisplayName("a putaway takes only the status its task puts away: INBOUND for a receipt, QUARANTINE for a release")
        void putAwayTakesItsOwnSource() {
            StockPackage inbound = inStock();
            assertThatThrownBy(() -> inbound.putAway(StockStatus.QUARANTINE)).isInstanceOf(IllegalStateException.class);
            assertThat(inbound.stockStatus()).isEqualTo(StockStatus.INBOUND);

            StockPackage quarantined = inStock();
            quarantined.reclassify(StockStatus.QUARANTINE);
            assertThatThrownBy(() -> quarantined.putAway(StockStatus.INBOUND))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(quarantined.stockStatus()).isEqualTo(StockStatus.QUARANTINE);

            assertThatThrownBy(() -> inStock().putAway(StockStatus.AVAILABLE))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a free AVAILABLE or INBOUND carton is quarantined in place (20 §4.8, 4C-6); nothing else is")
        void quarantine() {
            StockPackage onSale = available();
            onSale.quarantine();
            assertThat(onSale.stockStatus()).isEqualTo(StockStatus.QUARANTINE);
            assertThat(onSale.location()).isEqualTo(BIN);
            assertThat(onSale.unitsInStock()).as("nothing changes in quantity").isEqualTo(1000);
            assertThat(onSale.unitsAvailable()).isZero();

            StockPackage inbound = inStock();
            inbound.quarantine();
            assertThat(inbound.stockStatus()).isEqualTo(StockStatus.QUARANTINE);

            assertThatThrownBy(onSale::quarantine).as("already in quarantine")
                    .isInstanceOf(IllegalStateException.class);
            StockPackage blocked = inStock();
            blocked.reclassify(StockStatus.BLOCKED);
            assertThatThrownBy(blocked::quarantine).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> labelled().quarantine()).as("not in stock")
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a carton held for an order is not quarantined under its hold (20 BR-21)")
        void quarantineRefusesAHeldCarton() {
            StockPackage carton = held();

            assertThatThrownBy(carton::quarantine).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("held for order");

            assertThat(carton.stockStatus()).isEqualTo(StockStatus.AVAILABLE);
            assertThat(carton.isHeld()).isTrue();
        }

        @Test
        @DisplayName("a held carton is neither written off nor lost from under its hold: the hold goes first")
        void aHeldCartonDoesNotLeaveByADecision() {
            StockPackage carton = held();

            assertThatThrownBy(() -> carton.consume(NOW)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> carton.lose(NOW)).isInstanceOf(IllegalStateException.class);

            assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(carton.isHeld()).isTrue();
        }
    }

    // ------------------------------------------------------------------ allocations

    @Nested
    @DisplayName("allocating")
    class Allocating {

        @Test
        @DisplayName("takes the carton for the order without deducting - nothing has physically moved")
        void allocationHoldsWithoutDeducting() {
            StockPackage carton = available();

            Reservation allocation = allocate(carton);

            assertThat(allocation.status()).isEqualTo(ReservationStatus.HELD);
            assertThat(carton.unitsInStock()).isEqualTo(1000);
            assertThat(carton.unitsHeld()).isEqualTo(1000);
            assertThat(carton.unitsAvailable()).isZero();
            assertThat(carton.isHeld()).isTrue();
            assertThat(carton.isFree()).isFalse();
            assertThat(carton.liveAllocation()).contains(allocation);
        }

        @Test
        @DisplayName("refuses to oversell: a carton given to one order is not given to a second")
        void allocatingAHeldCartonIsRejected() {
            StockPackage carton = held();

            assertThatThrownBy(() -> carton.allocate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), 1000, NOW))
                    .isInstanceOfSatisfying(InsufficientStockException.class, e -> {
                        assertThat(e.sku()).isEqualTo(SKU.code());
                        assertThat(e.requested()).isEqualTo(1000);
                        assertThat(e.available()).isZero();
                    });

            assertThat(carton.reservations()).as("one live allocation, never two").hasSize(1);
        }

        @Test
        @DisplayName("ADR-0011: a carton is allocated whole to a hold of its count - never for another count")
        void onlyACartonOfTheCountAskedFor() {
            StockPackage carton = available();

            assertThatThrownBy(() -> carton.allocate(ORDER, SKU_HOLD, SKU_HOLD, UUID.randomUUID(), 500, NOW))
                    .isInstanceOf(InsufficientStockException.class);
            assertThatThrownBy(() -> carton.allocate(ORDER, SKU_HOLD, SKU_HOLD, UUID.randomUUID(), 2000, NOW))
                    .isInstanceOf(InsufficientStockException.class);

            assertThat(carton.isFree()).isTrue();
            assertThat(carton.reservations()).isEmpty();
        }

        @Test
        @DisplayName("only AVAILABLE stock in stock is allocated: not quarantined, inbound, or out of stock")
        void allocatingUnsellableStockIsRejected() {
            StockPackage quarantined = available();
            quarantined.quarantine();
            assertThatThrownBy(() -> allocate(quarantined)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> allocate(inStock())).as("INBOUND").isInstanceOf(IllegalStateException.class);
            StockPackage consumed = available();
            consumed.consume(NOW);
            assertThatThrownBy(() -> allocate(consumed)).as("CONSUMED").isInstanceOf(IllegalStateException.class);
            assertThat(quarantined.reservations()).isEmpty();
        }

        @Test
        @DisplayName("the same request id allocates once, however many times it arrives")
        void allocationIsIdempotent() {
            StockPackage carton = available();
            UUID requestId = UUID.randomUUID();

            Reservation first = carton.allocate(ORDER, SKU_HOLD, SKU_HOLD, requestId, 1000, NOW);
            Reservation again = carton.allocate(ORDER, SKU_HOLD, SKU_HOLD, requestId, 1000, NOW.plusSeconds(5));

            assertThat(again).isSameAs(first);
            assertThat(carton.reservations()).hasSize(1);
            assertThat(carton.pullDomainEvents()).as("announced once").hasSize(1);
        }

        @Test
        @DisplayName("the hold for one caller request is findable before any replanning")
        void activeReservationsForFindsTheCallersHold() {
            StockPackage carton = available();
            UUID root = UUID.randomUUID();
            Reservation allocation = carton.allocate(ORDER, SKU_HOLD, root, UUID.randomUUID(), 1000, NOW);

            assertThat(carton.activeReservationsFor(root)).containsExactly(allocation);
            assertThat(carton.activeReservationsFor(UUID.randomUUID())).isEmpty();
        }

        @Test
        @DisplayName("an allocation is one carton of its count and names its SKU reservation")
        void anAllocationIsPinnedToItsSkuReservation() {
            StockPackage carton = available();
            SkuReservation from = SkuReservation.holdAt("HCM", SKU, ORDER, UUID.randomUUID(), 2, 1000, 5, NOW);

            Reservation allocation = carton.allocate(from, UUID.randomUUID(), NOW);

            assertThat(allocation.allocatedFrom()).isEqualTo(from.id().value());
            assertThat(allocation.rootRequestId()).isEqualTo(from.id().value());
            assertThat(allocation.orderId()).isEqualTo(ORDER);
            assertThat(allocation.unitsPerCarton()).isEqualTo(1000);
            assertThat(allocation.quantity().value()).isEqualTo(1000);
            assertThat(allocation.isAllocation()).isTrue();

            SkuReservation other = SkuReservation.holdAt("HCM", new Sku("CUP-PP-700"), ORDER, UUID.randomUUID(), 1,
                    1000, 5, NOW);
            assertThatThrownBy(() -> available().allocate(other, UUID.randomUUID(), NOW))
                    .as("a hold of another SKU").isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("announces StockAllocated, in units, so other modules can react")
        void allocationRegistersAnEvent() {
            StockPackage carton = available();

            Reservation allocation = allocate(carton);

            StockAllocated event = payload(carton, StockAllocated.class);
            assertThat(event.orderId()).isEqualTo(ORDER);
            assertThat(event.reservationId()).isEqualTo(allocation.id().value());
            assertThat(event.sku()).isEqualTo(SKU.code());
            assertThat(event.locationCode()).isEqualTo(BIN.code());
            assertThat(event).isEqualTo(new StockAllocated(ORDER, allocation.id().value(), SKU.code(), BIN.code(),
                    "LOT-1", 1000));
        }
    }

    @Nested
    @DisplayName("releasing")
    class Releasing {

        @Test
        @DisplayName("frees the carton again, and says so")
        void releaseFreesTheCarton() {
            StockPackage carton = available();
            Reservation allocation = allocate(carton);
            carton.pullDomainEvents();

            carton.releaseReservation(allocation.id(), ReleaseReason.ORDER_CANCELLED, NOW);

            assertThat(allocation.status()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(carton.isFree()).isTrue();
            assertThat(carton.unitsAvailable()).isEqualTo(1000);
            assertThat(payload(carton, StockReservationReleased.class)).isEqualTo(new StockReservationReleased(ORDER,
                    SKU.code(), 1000, ReleaseReason.ORDER_CANCELLED.toContract()));
        }

        @Test
        @DisplayName("releasing twice is harmless")
        void releaseIsIdempotent() {
            StockPackage carton = available();
            Reservation allocation = allocate(carton);
            carton.releaseReservation(allocation.id(), ReleaseReason.ORDER_CANCELLED, NOW);
            carton.pullDomainEvents();

            carton.releaseReservation(allocation.id(), ReleaseReason.MANUAL_OVERRIDE, NOW.plusSeconds(5));

            assertThat(allocation.releaseReason()).isEqualTo(ReleaseReason.ORDER_CANCELLED);
            assertThat(carton.pullDomainEvents()).as("nothing announced the second time").isEmpty();
            assertThatThrownBy(() -> carton.releaseReservation(ReservationId.of(UUID.randomUUID()),
                    ReleaseReason.MANUAL_OVERRIDE, NOW)).as("a hold of another carton")
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a released hold does not make the request look already-served: the carton is allocated anew")
        void releasedHoldsDoNotCountAsIdempotencyHits() {
            StockPackage carton = available();
            UUID requestId = UUID.randomUUID();
            Reservation first = carton.allocate(ORDER, SKU_HOLD, SKU_HOLD, requestId, 1000, NOW);
            carton.releaseReservation(first.id(), ReleaseReason.ORDER_CANCELLED, NOW);

            assertThat(carton.findByRequestId(requestId)).isEmpty();
            assertThat(carton.activeReservationsFor(SKU_HOLD)).isEmpty();
            Reservation second = carton.allocate(ORDER, SKU_HOLD, SKU_HOLD, UUID.randomUUID(), 1000, NOW);

            assertThat(second.id()).isNotEqualTo(first.id());
            assertThat(carton.liveAllocation()).contains(second);
            assertThat(carton.reservations()).as("the closed one stays as history").hasSize(2);
        }
    }

    @Nested
    @DisplayName("deducting")
    class Deducting {

        @Test
        @DisplayName("only a live hold becomes a real deduction: the hold CONSUMED, the carton out of stock, from where it stood")
        void consumingAReservationTakesTheCartonOutOfStock() {
            StockPackage carton = available();
            Reservation allocation = allocate(carton);
            carton.pullDomainEvents();

            carton.consumeReservation(allocation.id(), NOW);

            assertThat(allocation.status()).isEqualTo(ReservationStatus.CONSUMED);
            assertThat(carton.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(carton.stockStatus()).isNull();
            assertThat(carton.closedAt()).isEqualTo(NOW);
            assertThat(carton.unitsInStock()).isZero();
            assertThat(carton.isHeld()).isFalse();
            assertThat(carton.lotId()).as("its lot stays: it has been in stock").isEqualTo(LOT);
            assertThat(payload(carton, StockDeducted.class)).isEqualTo(new StockDeducted(ORDER, SKU.code(),
                    BIN.code(), 1000));
        }

        @Test
        @DisplayName("a staged carton is deducted from the staging area it stands in")
        void consumingAStagedCartonNamesItsPlace() {
            StockPackage carton = held();
            carton.moveTo(new LocationId("HCM-STG01"));
            Reservation allocation = carton.liveAllocation().orElseThrow();

            carton.consumeReservation(allocation.id(), NOW);

            assertThat(payload(carton, StockDeducted.class).locationCode()).isEqualTo("HCM-STG01");
        }

        @Test
        @DisplayName("a released hold cannot then be shipped")
        void consumingAReleasedReservationIsRejected() {
            StockPackage carton = available();
            Reservation allocation = allocate(carton);
            carton.releaseReservation(allocation.id(), ReleaseReason.ORDER_CANCELLED, NOW);

            assertThat(codeOf(() -> carton.consumeReservation(allocation.id(), NOW)))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE);
            assertThat(carton.status()).as("the carton stays in stock").isEqualTo(StockPackageStatus.IN_STOCK);
        }

        @Test
        @DisplayName("a hold consumed once cannot be consumed again")
        void consumingTwiceIsRejected() {
            StockPackage carton = available();
            Reservation allocation = allocate(carton);
            carton.consumeReservation(allocation.id(), NOW);

            assertThat(codeOf(() -> carton.consumeReservation(allocation.id(), NOW)))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE);
        }

        @Test
        @DisplayName("a hold of another carton is not found here")
        void consumingAnUnknownHoldIsNotFound() {
            StockPackage carton = held();

            assertThat(codeOf(() -> carton.consumeReservation(ReservationId.of(UUID.randomUUID()), NOW)))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
            assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
        }

        @Test
        @DisplayName("put-back: a picked carton comes back with its consumed hold as history, free to allocate again")
        void aPickedCartonPutBackIsFreeAgain() {
            StockPackage carton = available();
            Reservation picked = allocate(carton);
            carton.consumeReservation(picked.id(), NOW);

            carton.restock(BIN, StockStatus.AVAILABLE);

            assertThat(carton.isFree()).isTrue();
            assertThat(carton.unitsAvailable()).isEqualTo(1000);
            assertThat(carton.findReservation(picked.id())).get().extracting(Reservation::status)
                    .isEqualTo(ReservationStatus.CONSUMED);
            assertThat(carton.receivedAt()).isEqualTo(RECEIVED);
            Reservation again = allocate(carton);
            assertThat(carton.liveAllocation()).contains(again);
        }
    }

    @Nested
    @DisplayName("a held carton that is not there")
    class GivenUp {

        @Test
        @DisplayName("a count's shortage gives the hold up as a short pick and announces it; then the carton is lost")
        void holdsOfAWrittenOffCartonAreGivenUp() {
            StockPackage carton = held();
            Reservation hold = carton.liveAllocation().orElseThrow();

            carton.giveUpHoldsOfWrittenOffCarton(DECIDER, NOW);

            assertThat(hold.status()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(hold.releaseReason()).isEqualTo(ReleaseReason.SHORT_PICKED);
            List<Object> payloads = carton.pullDomainEvents().stream().map(e -> ((StockEvent) e).payload()).toList();
            assertThat(payloads).containsExactly(
                    new StockReservationReleased(ORDER, SKU.code(), 1000, ReleaseReason.SHORT_PICKED.toContract()),
                    new StockHeldCartonWrittenOff(ORDER, hold.id().value(), SKU.code(), BIN.code(), 1000, DECIDER));

            carton.lose(NOW);
            assertThat(carton.status()).isEqualTo(StockPackageStatus.LOST);
        }

        @Test
        @DisplayName("nothing happens on a carton with no live hold")
        void nothingToGiveUp() {
            StockPackage carton = available();

            carton.giveUpHoldsOfWrittenOffCarton(DECIDER, NOW);

            assertThat(carton.pullDomainEvents()).isEmpty();
        }

        @Test
        @DisplayName("a count's write-off is announced in units, at the carton's warehouse")
        void countWriteOffIsAnnounced() {
            StockPackage carton = available();

            carton.announceCountWriteOff(DECIDER);

            assertThat(payload(carton, StockWrittenOffByCount.class)).isEqualTo(new StockWrittenOffByCount(SKU.code(),
                    "HCM", 1000, DECIDER));
        }
    }

    // ------------------------------------------------------------------ invariants

    @Nested
    @DisplayName("invariants")
    class Invariants {

        private final StockPackageId id = new StockPackageId(UUID.randomUUID());
        private final UUID item = UUID.randomUUID();
        private final UUID unit = UUID.randomUUID();

        private StockPackage stored(String lpn, int nominal, Instant countedAt, StockPackageStatus status,
                                    StockStatus stockStatus, UUID lot, Instant closedAt,
                                    List<Reservation> reservations) {
            return new StockPackage(id, lpn, SKU, item, unit, nominal, countedAt, BIN, status, stockStatus, lot,
                    lot == null ? null : "LOT-1", lot == null ? null : RECEIVED, null, closedAt, reservations, 0);
        }

        private Reservation hold(ReservationStatus status) {
            return new Reservation(ReservationId.of(UUID.randomUUID()), ORDER, SKU_HOLD, UUID.randomUUID(), 5, NOW,
                    status, null, status == ReservationStatus.HELD ? null : NOW, SKU_HOLD);
        }

        @Test
        @DisplayName("a row that breaks the schema's checks is never rehydrated")
        void corruptedRows() {
            assertThatThrownBy(() -> stored("bad lpn", 5, null, StockPackageStatus.RECEIVING, null, null, null,
                    List.of())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> stored("LPN-1", 0, null, StockPackageStatus.RECEIVING, null, null, null,
                    List.of())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> stored("LPN-1", 5, null, StockPackageStatus.IN_STOCK, StockStatus.AVAILABLE, LOT,
                    null, List.of())).as("in stock, never counted").isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> stored("LPN-1", 5, NOW, StockPackageStatus.CONSUMED, null, LOT, null, List.of()))
                    .as("closed without closed_at").isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> stored("LPN-1", 5, null, StockPackageStatus.LOST, null, LOT, NOW, List.of()))
                    .as("lost, never counted").isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a carton has a stock status exactly while it is in stock (ck_stock_package_stock_status)")
        void stockStatusExactlyWhileInStock() {
            assertThatThrownBy(() -> stored("LPN-1", 5, NOW, StockPackageStatus.IN_STOCK, null, LOT, null, List.of()))
                    .as("in stock with no stock status").isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> stored("LPN-1", 5, NOW, StockPackageStatus.CONSUMED, StockStatus.AVAILABLE, LOT,
                    NOW, List.of())).as("the stale status of a consumed carton is not kept")
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> stored("LPN-1", 5, null, StockPackageStatus.RECEIVING, StockStatus.INBOUND, null,
                    null, List.of())).as("not in stock yet").isInstanceOf(IllegalStateException.class);
            assertThat(stored("LPN-1", 5, NOW, StockPackageStatus.IN_STOCK, StockStatus.QUARANTINE, LOT, null,
                    List.of()).unitsInStock()).isEqualTo(5);
        }

        @Test
        @DisplayName("a carton that has been in stock has its lot (ck_stock_package_lot); one being received or voided needs none")
        void lotOnceInStock() {
            assertThatThrownBy(() -> stored("LPN-1", 5, NOW, StockPackageStatus.IN_STOCK, StockStatus.AVAILABLE, null,
                    null, List.of())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> stored("LPN-1", 5, NOW, StockPackageStatus.CONSUMED, null, null, NOW, List.of()))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> stored("LPN-1", 5, NOW, StockPackageStatus.LOST, null, null, NOW, List.of()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(stored("LPN-1", 5, null, StockPackageStatus.RECEIVING, null, null, null, List.of()).lotId())
                    .isNull();
            assertThat(stored("LPN-1", 5, null, StockPackageStatus.VOID, null, null, NOW, List.of()).lotId())
                    .isNull();
        }

        @Test
        @DisplayName("at most one live allocation, and only on a carton in stock (uk_stock_reservation_live_carton)")
        void atMostOneLiveAllocation() {
            assertThatThrownBy(() -> stored("LPN-1", 5, NOW, StockPackageStatus.IN_STOCK, StockStatus.AVAILABLE, LOT,
                    null, List.of(hold(ReservationStatus.HELD), hold(ReservationStatus.HELD))))
                    .as("one carton given to two orders").isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> stored("LPN-1", 5, NOW, StockPackageStatus.CONSUMED, null, LOT, NOW,
                    List.of(hold(ReservationStatus.HELD)))).as("a live hold on a carton that is gone")
                    .isInstanceOf(IllegalStateException.class);

            StockPackage carton = stored("LPN-1", 5, NOW, StockPackageStatus.IN_STOCK, StockStatus.AVAILABLE, LOT,
                    null, List.of(hold(ReservationStatus.HELD), hold(ReservationStatus.CONSUMED),
                            hold(ReservationStatus.RELEASED)));
            assertThat(carton.isHeld()).as("closed holds are history, not a second allocation").isTrue();
            assertThat(carton.unitsHeld()).isEqualTo(5);
            assertThat(carton.unitsAvailable()).isZero();
        }

        @Test
        @DisplayName("units, for whoever still speaks them: the carton's count while in stock, held or free - whole or nothing")
        void unitsAreTheCountOrNothing() {
            StockPackage free = stored("LPN-1", 5, NOW, StockPackageStatus.IN_STOCK, StockStatus.AVAILABLE, LOT, null,
                    List.of());
            assertThat(List.of(free.unitsInStock(), free.unitsHeld(), free.unitsAvailable())).containsExactly(5, 0, 5);
            assertThat(free.isFree()).isTrue();

            StockPackage quarantined = stored("LPN-1", 5, NOW, StockPackageStatus.IN_STOCK, StockStatus.QUARANTINE,
                    LOT, null, List.of());
            assertThat(List.of(quarantined.unitsInStock(), quarantined.unitsHeld(), quarantined.unitsAvailable()))
                    .containsExactly(5, 0, 0);

            StockPackage gone = stored("LPN-1", 5, NOW, StockPackageStatus.CONSUMED, null, LOT, NOW, List.of());
            assertThat(List.of(gone.unitsInStock(), gone.unitsHeld(), gone.unitsAvailable())).containsExactly(0, 0, 0);
        }

        @Test
        @DisplayName("an undated carton stays undated: its receipt time is not known, and coming back does not date it")
        void anUndatedCartonStaysUndated() {
            StockPackage undated = new StockPackage(id, "LPN-1", SKU, item, unit, 5, NOW, BIN,
                    StockPackageStatus.CONSUMED, null, LOT, "LOT-1", null, null, NOW, List.of(), 0);

            undated.restock(BIN, StockStatus.AVAILABLE);

            assertThat(undated.receivedAt()).isNull();
            assertThat(undated.lotId()).isEqualTo(LOT);
        }
    }
}
