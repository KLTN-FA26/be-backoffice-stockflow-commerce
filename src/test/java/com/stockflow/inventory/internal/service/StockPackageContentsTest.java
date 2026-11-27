package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.AdjustmentDirection;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReturnedCartonOwnership;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static com.stockflow.inventory.internal.service.InventoryFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCRUM-473 / SCRUM-164, on the carton that is the stock (ADR-0011): an approved adjustment writes a carton off or
 * restocks it as a whole, a picked or returned carton comes back whole, and each of these locks the carton and changes
 * it once. There is no row beside the carton to keep true to it any more: the cases that took units out of part of a
 * carton, or settled a carton against its rows, pinned states that cannot exist.
 */
class StockPackageContentsTest {

    private static final LocationId BIN = new LocationId("HCM-A01-1-A");
    private static final UUID LOT = UUID.randomUUID();
    private static final Instant RECEIVED = NOW.minusSeconds(86_400);

    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final com.stockflow.inventory.internal.domain.LocationDirectory locations =
            mock(com.stockflow.inventory.internal.domain.LocationDirectory.class);
    private final ReturnedCartonOwnership ownership = mock(ReturnedCartonOwnership.class);
    private final StockPackageContents contents = new StockPackageContents(stockPackages, locations, ownership);

    private final StockPackageId id = new StockPackageId(UUID.randomUUID());

    @BeforeEach
    void saves() {
        when(stockPackages.save(any())).thenAnswer(call -> call.getArgument(0));
        when(stockPackages.saveNow(any())).thenAnswer(call -> call.getArgument(0));
        when(ownership.owned(any())).thenReturn(Optional.empty());
    }

    /** A carton of {@code units} in {@code status}; in stock, in stock status {@code stockStatus}. The one the repository locks. */
    private StockPackage carton(StockPackageStatus status, StockStatus stockStatus, int units) {
        StockPackage carton = new StockPackage(id, "LPN-0001", new Sku("CUP-12OZ"), UUID.randomUUID(),
                UUID.randomUUID(), units, NOW, BIN, status, stockStatus, LOT, "L1", RECEIVED, UUID.randomUUID(),
                status.isClosed() ? NOW : null, List.of(), 0L);
        when(stockPackages.findByIdForUpdate(id)).thenReturn(Optional.of(carton));
        return carton;
    }

    private StockPackage available(int units) {
        return carton(StockPackageStatus.IN_STOCK, StockStatus.AVAILABLE, units);
    }

    private static Reservation heldForAnOrder(StockPackage carton) {
        Reservation hold = carton.allocate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                carton.nominalQty(), NOW);
        carton.pullDomainEvents();
        return hold;
    }

    @Nested
    @DisplayName("an approved adjustment of a carton")
    class Adjusting {

        @Test
        @DisplayName("an adjustment that says no direction is a caller's bug")
        void noDirection() {
            assertThatThrownBy(() -> contents.adjust(id, null, false, NOW)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("an unknown carton is a data fault")
        void unknownCarton() {
            when(stockPackages.findByIdForUpdate(id)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> contents.adjust(id, AdjustmentDirection.WRITE_OFF, false, NOW))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("written off whole: CONSUMED, or LOST when a count did not find it or it was written off as lost; "
                + "the change says its units, the status it had and where it stood")
        void writeDownToNothing() {
            StockPackage consumed = available(24);
            StockPackageContents.CartonChange change = contents.adjust(id, AdjustmentDirection.WRITE_OFF, false, NOW);
            assertThat(consumed.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(consumed.closedAt()).isEqualTo(NOW);
            assertThat(consumed.stockStatus()).isNull();
            assertThat(consumed.unitsInStock()).isZero();
            assertThat(change.carton()).isSameAs(consumed);
            assertThat(change.delta()).isEqualTo(-24);
            assertThat(change.status()).as("captured before it left stock: the ledger line needs it")
                    .isEqualTo(StockStatus.AVAILABLE);
            assertThat(change.location()).isEqualTo(BIN);
            verify(stockPackages).save(consumed);

            StockPackage lost = available(24);
            contents.adjust(id, AdjustmentDirection.WRITE_OFF, true, NOW);
            assertThat(lost.status()).isEqualTo(StockPackageStatus.LOST);
        }

        @Test
        @DisplayName("a quarantined carton is written off as it is (20 §4.8 step 3): the change keeps QUARANTINE")
        void quarantinedCartonIsWrittenOff() {
            StockPackage carton = carton(StockPackageStatus.IN_STOCK, StockStatus.QUARANTINE, 24);

            StockPackageContents.CartonChange change = contents.adjust(id, AdjustmentDirection.WRITE_OFF, false, NOW);

            assertThat(carton.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(change.status()).isEqualTo(StockStatus.QUARANTINE);
        }

        @Test
        @DisplayName("a carton held for an order is not written off: a customer's hold is never cancelled by a correction")
        void writeDownCannotCutIntoHolds() {
            StockPackage carton = available(24);
            Reservation hold = heldForAnOrder(carton);

            assertThat(codeOf(() -> contents.adjust(id, AdjustmentDirection.WRITE_OFF, false, NOW)))
                    .isEqualTo(ErrorCode.STOCK_PACKAGE_HELD);

            assertThat(carton.unitsInStock()).isEqualTo(24);
            assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(carton.liveAllocation()).contains(hold);
            verify(stockPackages, never()).save(any());
        }

        @Test
        @DisplayName("a count's shortage of a held carton is written off and the hold given up (SHORT_PICKED) and "
                + "announced, naming who decided - 20 §4.2 step 8")
        void shortageOfAHeldCartonGivesUpTheHold() {
            StockPackage carton = available(24);
            Reservation hold = heldForAnOrder(carton);
            UUID approver = UUID.randomUUID();

            StockPackageContents.CartonChange change =
                    contents.adjust(id, AdjustmentDirection.WRITE_OFF, true, approver, NOW);

            assertThat(change.delta()).isEqualTo(-24);
            assertThat(carton.unitsInStock()).isZero();
            assertThat(carton.isHeld()).isFalse();
            assertThat(hold.status()).isEqualTo(com.stockflow.inventory.internal.domain.ReservationStatus.RELEASED);
            assertThat(hold.releaseReason()).isEqualTo(com.stockflow.inventory.internal.domain.ReleaseReason.SHORT_PICKED);
            assertThat(carton.status()).isEqualTo(StockPackageStatus.LOST);
            List<Object> payloads = change.carton().pullDomainEvents().stream()
                    .map(e -> ((com.stockflow.inventory.internal.domain.StockEvent) e).payload()).toList();
            assertThat(payloads).as("the carton handed back still carries its events: the caller publishes them")
                    .contains(new com.stockflow.contracts.StockHeldCartonWrittenOff(hold.orderId(), hold.id().value(),
                                    "CUP-12OZ", "HCM-A01-1-A", 24, approver),
                            new com.stockflow.contracts.StockWrittenOffByCount("CUP-12OZ", "HCM", 24, approver));
        }

        @Test
        @DisplayName("a count's shortage of a free carton is announced too: the warehouse has a carton fewer")
        void shortageOfAFreeCartonIsAnnounced() {
            StockPackage carton = available(24);
            UUID approver = UUID.randomUUID();

            contents.adjust(id, AdjustmentDirection.WRITE_OFF, true, approver, NOW);

            assertThat(carton.pullDomainEvents()).extracting(e ->
                            ((com.stockflow.inventory.internal.domain.StockEvent) e).payload())
                    .containsExactly(new com.stockflow.contracts.StockWrittenOffByCount("CUP-12OZ", "HCM", 24,
                            approver));
        }

        @Test
        @DisplayName("without a decider (any adjustment but a count variance) a held carton is still refused, "
                + "the hold kept")
        void noDeciderNoGivingUp() {
            StockPackage carton = available(24);
            Reservation hold = heldForAnOrder(carton);

            assertThat(codeOf(() -> contents.adjust(id, AdjustmentDirection.WRITE_OFF, true, null, NOW)))
                    .isEqualTo(ErrorCode.STOCK_PACKAGE_HELD);

            assertThat(hold.status()).isEqualTo(com.stockflow.inventory.internal.domain.ReservationStatus.HELD);
            assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
        }

        @Test
        @DisplayName("only an IN_STOCK carton can be written off")
        void writeDownNeedsInStock() {
            carton(StockPackageStatus.CONSUMED, null, 24);

            assertThat(codeOf(() -> contents.adjust(id, AdjustmentDirection.WRITE_OFF, false, NOW)))
                    .isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
        }

        @Test
        @DisplayName("a carton in stock is whole: nothing is written up onto it")
        void writeUpOfACartonInStockIsRefused() {
            StockPackage carton = available(24);

            assertThat(codeOf(() -> contents.adjust(id, AdjustmentDirection.RESTOCK, false, NOW)))
                    .isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
            assertThat(carton.unitsInStock()).isEqualTo(24);
        }

        @Test
        @DisplayName("a LOST carton that is found goes back IN_STOCK whole, sellable, with the lot and the FIFO date it had")
        void lostCartonIsFound() {
            StockPackage lost = carton(StockPackageStatus.LOST, null, 24);

            StockPackageContents.CartonChange change = contents.adjust(id, AdjustmentDirection.RESTOCK, false, NOW);

            assertThat(lost.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(lost.closedAt()).isNull();
            assertThat(lost.unitsInStock()).isEqualTo(24);
            assertThat(lost.stockStatus()).as("L4: found on the shelf, it is on sale - never silently out of ATP")
                    .isEqualTo(StockStatus.AVAILABLE);
            assertThat(lost.unitsAvailable()).isEqualTo(24);
            assertThat(lost.lotId()).isEqualTo(LOT);
            assertThat(lost.receivedAt()).as("it keeps its age: FIFO is not told it is new").isEqualTo(RECEIVED);
            assertThat(change.delta()).isEqualTo(24);
            assertThat(change.status()).isEqualTo(StockStatus.AVAILABLE);
            assertThat(change.location()).isEqualTo(BIN);
            verify(stockPackages).save(lost);
        }

        @Test
        @DisplayName("L4: a LOST carton recorded in a QUARANTINE area comes back QUARANTINE, out of ATP")
        void foundCartonInQuarantineStaysInQuarantine() {
            when(locations.isQuarantineArea(BIN)).thenReturn(true);
            StockPackage lost = carton(StockPackageStatus.LOST, null, 24);

            StockPackageContents.CartonChange change = contents.adjust(id, AdjustmentDirection.RESTOCK, false, NOW);

            assertThat(lost.stockStatus()).isEqualTo(StockStatus.QUARANTINE);
            assertThat(lost.unitsInStock()).isEqualTo(24);
            assertThat(lost.unitsAvailable()).as("not sellable until released").isZero();
            assertThat(change.status()).isEqualTo(StockStatus.QUARANTINE);
        }

        @Test
        @DisplayName("a consumed or void carton takes nothing back")
        void closedCartonTakesNothing() {
            carton(StockPackageStatus.CONSUMED, null, 24);

            BusinessException refused = catchThrowableOfType(BusinessException.class,
                    () -> contents.adjust(id, AdjustmentDirection.RESTOCK, false, NOW));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
        }

        @Test
        @DisplayName("WF-05 Q-4: a carton back on a return whose refund is undecided is not the company's to adjust")
        void aReturnedCartonAwaitingItsRefundIsNotAdjusted() {
            StockPackage carton = carton(StockPackageStatus.IN_STOCK, StockStatus.QUARANTINE, 24);
            when(ownership.owned(id)).thenReturn(Optional.of(new ReturnedCartonOwnership.Owned(
                    ReturnedCartonOwnership.Ownership.PENDING, UUID.randomUUID())));

            assertThat(codeOf(() -> contents.adjust(id, AdjustmentDirection.WRITE_OFF, false, NOW)))
                    .isEqualTo(ErrorCode.STOCK_PACKAGE_DECISION_PENDING);
            assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            verify(stockPackages, never()).save(any());
        }
    }

    @Nested
    @DisplayName("a carton that comes back whole")
    class ComingBack {

        private final LocationId other = new LocationId("HCM-A02-1-A");
        private final LocationId quarantine = new LocationId("HCM-QC01");

        @Test
        @DisplayName("put back after its order was cancelled: CONSUMED to IN_STOCK at the bin, AVAILABLE, written at once")
        void restockedAsAvailable() {
            StockPackage picked = carton(StockPackageStatus.CONSUMED, null, 24);

            StockPackage back = contents.restock(id, other, false);

            assertThat(back).isSameAs(picked);
            assertThat(picked.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(picked.stockStatus()).isEqualTo(StockStatus.AVAILABLE);
            assertThat(picked.location()).isEqualTo(other);
            assertThat(picked.receivedAt()).isEqualTo(RECEIVED);
            // Written before the caller's ledger line names it at its new place (check_stock_movement_package).
            verify(stockPackages).saveNow(picked);
        }

        @Test
        @DisplayName("removed at the check station as damaged: back QUARANTINE, never AVAILABLE (4C-6)")
        void restockedIntoQuarantine() {
            StockPackage picked = carton(StockPackageStatus.CONSUMED, null, 24);

            contents.restock(id, quarantine, true);

            assertThat(picked.stockStatus()).isEqualTo(StockStatus.QUARANTINE);
            assertThat(picked.location()).isEqualTo(quarantine);
            assertThat(picked.unitsAvailable()).isZero();
        }

        @Test
        @DisplayName("only a carton that left stock comes back: one in stock, lost or never received is refused")
        void onlyAConsumedCartonComesBack() {
            for (StockPackageStatus status : List.of(StockPackageStatus.IN_STOCK, StockPackageStatus.LOST)) {
                carton(status, status == StockPackageStatus.IN_STOCK ? StockStatus.AVAILABLE : null, 24);
                assertThat(codeOf(() -> contents.restock(id, other, false))).as("restock of %s", status)
                        .isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
                assertThat(codeOf(() -> contents.returnIntoQuarantine(id, quarantine))).as("return of %s", status)
                        .isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
            }
            verify(stockPackages, never()).saveNow(any());
        }

        @Test
        @DisplayName("back on a return receipt: CONSUMED to IN_STOCK in the quarantine area, QUARANTINE, in its own lot (4D-2)")
        void returnedIntoQuarantine() {
            StockPackage shipped = carton(StockPackageStatus.CONSUMED, null, 24);

            StockPackage back = contents.returnIntoQuarantine(id, quarantine);

            assertThat(back.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(back.stockStatus()).as("nobody has looked at the goods yet").isEqualTo(StockStatus.QUARANTINE);
            assertThat(back.location()).isEqualTo(quarantine);
            assertThat(back.lotId()).isEqualTo(LOT);
            assertThat(back.lpn()).isEqualTo(shipped.lpn());
            verify(stockPackages).saveNow(shipped);
        }

        @Test
        @DisplayName("WF-05 Q-4: a returned carton leaves the books again whole - QUARANTINE, held for nobody, to CONSUMED")
        void aReturnIsReversed() {
            StockPackage returned = carton(StockPackageStatus.IN_STOCK, StockStatus.QUARANTINE, 24);

            StockPackageContents.CartonChange change = contents.reverseReturn(id, NOW);

            assertThat(returned.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(returned.closedAt()).isEqualTo(NOW);
            assertThat(change.delta()).isEqualTo(-24);
            assertThat(change.status()).isEqualTo(StockStatus.QUARANTINE);
            assertThat(change.location()).isEqualTo(BIN);
            verify(stockPackages).save(returned);
        }

        @Test
        @DisplayName("a return is reversed only out of quarantine: a carton on sale is STOCK_PACKAGE_TAKEN_WHOLE, one "
                + "out of stock INVALID_STOCK_PACKAGE_TRANSITION")
        void aReturnIsReversedOnlyOutOfQuarantine() {
            StockPackage onSale = available(24);
            assertThat(codeOf(() -> contents.reverseReturn(id, NOW))).isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(onSale.status()).isEqualTo(StockPackageStatus.IN_STOCK);

            carton(StockPackageStatus.CONSUMED, null, 24);
            assertThat(codeOf(() -> contents.reverseReturn(id, NOW)))
                    .isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
            verify(stockPackages, never()).save(any());
        }
    }
}
