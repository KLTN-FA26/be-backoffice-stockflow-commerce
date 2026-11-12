package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static com.stockflow.inventory.internal.service.InventoryFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCRUM-473 / SCRUM-164: a carton stays true to its rows. Taking a carton's units closes it CONSUMED; taking part of
 * one is refused, as nothing opens a carton since team plan 4C-2; an approved adjustment writes the carton up or down
 * as a whole.
 */
class StockPackageContentsTest {

    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final StockItemRepository stockItems = mock(StockItemRepository.class);
    private final com.stockflow.inventory.internal.domain.LocationDirectory locations =
            mock(com.stockflow.inventory.internal.domain.LocationDirectory.class);
    private final StockPackageContents contents = new StockPackageContents(stockPackages, stockItems, locations,
            new org.springframework.beans.factory.support.StaticListableBeanFactory()
                    .getBeanProvider(com.stockflow.inventory.spi.ShortPickLines.class));

    private static StockPackage carton(StockPackageId id, int counted) {
        return new StockPackage(id, "LPN-0001", UUID.randomUUID(), UUID.randomUUID(), 24, counted,
                new LocationId("HCM-A01-1-A"), StockPackageStatus.IN_STOCK, UUID.randomUUID(), null, 0L);
    }

    private static StockPackage cartonWith(StockPackageId id, StockPackageStatus status, int counted) {
        Instant closed = status.isClosed() ? NOW : null;
        return new StockPackage(id, "LPN-0001", UUID.randomUUID(), UUID.randomUUID(), 24, counted,
                new LocationId("HCM-A01-1-A"), status, UUID.randomUUID(), closed, 0L);
    }

    private static StockItem row(StockPackageId carton, int onHand) {
        return new StockItem(StockItemId.newId(), new Sku("CUP-12OZ"), new LocationId("HCM-A01-1-A"), "L1", Quantity.of(onHand), StockStatus.AVAILABLE, List.of(), 0L, NOW, null, carton);
    }

    private static StockItem rowIn(StockPackageId carton, int onHand, StockStatus status) {
        return new StockItem(StockItemId.newId(), new Sku("CUP-12OZ"), new LocationId("HCM-A01-1-A"), "L1",
                Quantity.of(onHand), status, List.of(), 0L, NOW, null, carton);
    }

    private static StockItem loose(int onHand) {
        return row(null, onHand);
    }

    @Nested
    @DisplayName("taking units out of cartons (lock, take, settle)")
    class Taking {

        @Test
        @DisplayName("loose rows lock nothing")
        void looseRowsLockNothing() {
            StockPackageContents.LockedCartons locked = contents.lockFor(List.of(loose(5)));

            locked.tookFrom(loose(5));
            contents.settle(locked, NOW);

            verify(stockPackages, never()).findAllByIdForUpdate(anyCollection());
            verify(stockPackages, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("a carton taken from in part is refused (STOCK_PACKAGE_TAKEN_WHOLE): it is neither consumed nor saved")
        void partiallyTakenIsRefused() {
            StockPackageId id = new StockPackageId(UUID.randomUUID());
            StockPackage carton = carton(id, 24);
            StockItem row = row(id, 24);
            when(stockPackages.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(carton));
            when(stockItems.onHandInStockPackage(id)).thenReturn(14);

            StockPackageContents.LockedCartons locked = contents.lockFor(List.of(row));
            locked.tookFrom(row);

            assertThat(codeOf(() -> contents.settle(locked, NOW))).isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            verify(stockPackages, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("a carton emptied by the take is closed CONSUMED at that time")
        void emptiedIsConsumed() {
            StockPackageId id = new StockPackageId(UUID.randomUUID());
            StockPackage carton = carton(id, 24);
            StockItem row = row(id, 24);
            when(stockPackages.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(carton));
            when(stockItems.onHandInStockPackage(id)).thenReturn(0);

            StockPackageContents.LockedCartons locked = contents.lockFor(List.of(row));
            locked.tookFrom(row);
            contents.settle(locked, NOW);

            assertThat(carton.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(carton.closedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("a locked carton nobody took from is left as it was")
        void untouchedCartonIsNotSaved() {
            StockPackageId id = new StockPackageId(UUID.randomUUID());
            StockPackage carton = carton(id, 24);
            when(stockPackages.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(carton));

            StockPackageContents.LockedCartons locked = contents.lockFor(List.of(row(id, 24)));
            contents.settle(locked, NOW);

            verify(stockPackages, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("taking from a carton that was not locked first is the lock order broken: refused at once")
        void takingFromUnlockedCartonIsRefused() {
            StockPackageContents.LockedCartons locked = contents.lockFor(List.of(loose(5)));

            assertThatThrownBy(() -> locked.tookFrom(row(new StockPackageId(UUID.randomUUID()), 5)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("was not locked");
        }

        @Test
        @DisplayName("two rows of one carton lock it once, and settle it once")
        void oneCartonManyRows() {
            StockPackageId id = new StockPackageId(UUID.randomUUID());
            StockPackage carton = carton(id, 24);
            StockItem a = row(id, 10);
            StockItem b = row(id, 14);
            when(stockPackages.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(carton));
            when(stockItems.onHandInStockPackage(id)).thenReturn(0);

            StockPackageContents.LockedCartons locked = contents.lockFor(List.of(a, b));
            locked.tookFrom(a);
            locked.tookFrom(b);
            contents.settle(locked, NOW);

            verify(stockPackages, org.mockito.Mockito.times(1)).save(carton);
        }
    }

    @Nested
    @DisplayName("an approved adjustment of a carton")
    class Adjusting {

        private final StockPackageId id = new StockPackageId(UUID.randomUUID());

        private void rowsOf(StockPackage carton, StockItem... rows) {
            when(stockPackages.findAllByIdForUpdate(List.of(id))).thenReturn(List.of(carton));
            when(stockItems.findIdsInStockPackage(id)).thenReturn(java.util.Arrays.stream(rows).map(StockItem::id).toList());
            for (StockItem row : rows) {
                when(stockItems.findByIdForUpdate(row.id())).thenReturn(java.util.Optional.of(row));
            }
        }

        @Test
        @DisplayName("an adjustment that changes nothing is a caller's bug")
        void zeroDelta() {
            assertThatThrownBy(() -> contents.adjust(id, 0, false, NOW)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("an unknown carton is a data fault")
        void unknownCarton() {
            when(stockPackages.findAllByIdForUpdate(List.of(id))).thenReturn(List.of());

            assertThatThrownBy(() -> contents.adjust(id, -1, false, NOW)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a carton without a stock row cannot be adjusted")
        void noRows() {
            rowsOf(carton(id, 24));

            assertThatThrownBy(() -> contents.adjust(id, -1, false, NOW)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no stock row");
        }

        @Test
        @DisplayName("written down in part: refused before any row changes (STOCK_PACKAGE_TAKEN_WHOLE) - a count states "
                + "a carton present or missing, never a number of units (team plan 4C-3)")
        void writeDownInPartIsRefused() {
            StockPackage carton = carton(id, 24);
            StockItem only = row(id, 24);
            rowsOf(carton, only);

            assertThat(codeOf(() -> contents.adjust(id, -12, false, NOW)))
                    .isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(only.onHand().value()).isEqualTo(24);
            assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            verify(stockPackages, never()).save(org.mockito.ArgumentMatchers.any());
            verify(stockItems, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("written up in part, or by more than a carton, is refused too")
        void writeUpInPartIsRefused() {
            StockPackage lost = cartonWith(id, StockPackageStatus.LOST, 24);
            rowsOf(lost, row(id, 0));

            assertThat(codeOf(() -> contents.adjust(id, 4, false, NOW))).isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(codeOf(() -> contents.adjust(id, 48, false, NOW)))
                    .isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(lost.status()).isEqualTo(StockPackageStatus.LOST);
        }

        @Test
        @DisplayName("written off whole: CONSUMED, or LOST when a count did not find it or it was written off as lost")
        void writeDownToNothing() {
            StockPackage consumed = carton(id, 24);
            StockItem consumedRow = row(id, 24);
            rowsOf(consumed, consumedRow);
            List<StockPackageContents.RowChange> changes = contents.adjust(id, -24, false, NOW);
            assertThat(consumed.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(consumed.closedAt()).isEqualTo(NOW);
            assertThat(consumedRow.onHand().value()).isZero();
            assertThat(changes).singleElement().satisfies(c -> assertThat(c.delta()).isEqualTo(-24));

            StockPackage lost = carton(id, 24);
            rowsOf(lost, row(id, 24));
            contents.adjust(id, -24, true, NOW);
            assertThat(lost.status()).isEqualTo(StockPackageStatus.LOST);
        }

        @Test
        @DisplayName("a carton held for an order is not written off: a customer's hold is never cancelled by a correction")
        void writeDownCannotCutIntoHolds() {
            StockPackage carton = carton(id, 24);
            StockItem held = row(id, 24);
            held.allocate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(24), NOW);
            rowsOf(carton, held);

            assertThat(codeOf(() -> contents.adjust(id, -24, false, NOW))).isEqualTo(ErrorCode.STOCK_PACKAGE_HELD);

            assertThat(held.onHand().value()).isEqualTo(24);
            assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            verify(stockPackages, never()).save(org.mockito.ArgumentMatchers.any());
        }

        /** A contents whose pick-line question is answered: {@code shortHolds} are the holds of SHORT lines. */
        private StockPackageContents withShortLines(java.util.Set<UUID> shortHolds) {
            var lines = (com.stockflow.inventory.spi.ShortPickLines) ids -> {
                java.util.Set<UUID> answered = new java.util.HashSet<>(ids);
                answered.retainAll(shortHolds);
                return answered;
            };
            var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
            beans.addBean("shortPickLines", lines);
            return new StockPackageContents(stockPackages, stockItems, locations,
                    beans.getBeanProvider(com.stockflow.inventory.spi.ShortPickLines.class));
        }

        @Test
        @DisplayName("a count's shortage of a carton whose hold is a SHORT line's is written off, the hold given up "
                + "(SHORT_PICKED) with it - 20 §4.2 step 8")
        void shortageOfAShortLinesCartonGivesUpTheHold() {
            StockPackage carton = carton(id, 24);
            StockItem held = row(id, 24);
            var hold = held.allocate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    Quantity.of(24), NOW);
            rowsOf(carton, held);

            List<StockPackageContents.RowChange> changes =
                    withShortLines(java.util.Set.of(hold.id().value())).adjust(id, -24, true, true, NOW);

            assertThat(changes).singleElement().satisfies(c -> assertThat(c.delta()).isEqualTo(-24));
            assertThat(held.onHand().value()).isZero();
            assertThat(held.reserved().value()).isZero();
            assertThat(hold.status()).isEqualTo(com.stockflow.inventory.internal.domain.ReservationStatus.RELEASED);
            assertThat(hold.releaseReason()).isEqualTo(com.stockflow.inventory.internal.domain.ReleaseReason.SHORT_PICKED);
            assertThat(carton.status()).isEqualTo(StockPackageStatus.LOST);
            assertThat(held.pullDomainEvents()).as("the release is announced like any other").hasSize(2);
        }

        @Test
        @DisplayName("a hold whose line was not reported short is not given up, even for a count variance: refused, "
                + "nothing changes")
        void aHoldOfALineNotShortIsKept() {
            StockPackage carton = carton(id, 24);
            StockItem held = row(id, 24);
            var hold = held.allocate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    Quantity.of(24), NOW);
            rowsOf(carton, held);

            assertThat(codeOf(() -> withShortLines(java.util.Set.of()).adjust(id, -24, true, true, NOW)))
                    .isEqualTo(ErrorCode.STOCK_PACKAGE_HELD);

            assertThat(hold.status()).isEqualTo(com.stockflow.inventory.internal.domain.ReservationStatus.HELD);
            assertThat(held.onHand().value()).isEqualTo(24);
            assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
        }

        @Test
        @DisplayName("without the pick-line answer (inventory alone) or without asking for it, a held carton is refused")
        void noAnswerNoRelease() {
            StockPackage carton = carton(id, 24);
            StockItem held = row(id, 24);
            var hold = held.allocate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    Quantity.of(24), NOW);
            rowsOf(carton, held);

            assertThat(codeOf(() -> contents.adjust(id, -24, true, true, NOW))).isEqualTo(ErrorCode.STOCK_PACKAGE_HELD);
            assertThat(codeOf(() -> withShortLines(java.util.Set.of(hold.id().value())).adjust(id, -24, true, false, NOW)))
                    .isEqualTo(ErrorCode.STOCK_PACKAGE_HELD);
            assertThat(hold.status()).isEqualTo(com.stockflow.inventory.internal.domain.ReservationStatus.HELD);
        }

        @Test
        @DisplayName("only an IN_STOCK carton can be written off")
        void writeDownNeedsInStock() {
            rowsOf(cartonWith(id, StockPackageStatus.CONSUMED, 24), row(id, 0));

            assertThat(codeOf(() -> contents.adjust(id, -24, false, NOW)))
                    .isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
        }

        @Test
        @DisplayName("a carton in stock is whole: nothing is written up onto it")
        void writeUpOfACartonInStockIsRefused() {
            rowsOf(carton(id, 24), row(id, 24));

            assertThat(codeOf(() -> contents.adjust(id, 24, false, NOW)))
                    .isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
        }

        @Test
        @DisplayName("a LOST carton that is found goes back IN_STOCK whole on its own row, merged so it keeps its age")
        void lostCartonIsFound() {
            StockPackage lost = cartonWith(id, StockPackageStatus.LOST, 24);
            StockItem emptied = row(id, 0);
            rowsOf(lost, emptied);

            List<StockPackageContents.RowChange> changes = contents.adjust(id, 24, false, NOW);

            assertThat(lost.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(lost.closedAt()).isNull();
            assertThat(emptied.onHand().value()).isEqualTo(24);
            assertThat(changes).singleElement().satisfies(c -> assertThat(c.delta()).isEqualTo(24));
            verify(stockItems).saveMerged(emptied);
            verify(stockPackages).save(lost);
        }

        @Test
        @DisplayName("L4: a LOST carton whose row was INBOUND, BLOCKED or DAMAGED comes back AVAILABLE, not silently "
                + "out of ATP")
        void foundCartonIsSellableWhateverItsRowWas() {
            for (StockStatus before : List.of(StockStatus.INBOUND, StockStatus.BLOCKED, StockStatus.DAMAGED,
                    StockStatus.AVAILABLE)) {
                StockPackage lost = cartonWith(id, StockPackageStatus.LOST, 24);
                StockItem emptied = rowIn(id, 0, before);
                rowsOf(lost, emptied);

                contents.adjust(id, 24, false, NOW);

                assertThat(emptied.status()).as("a row that was " + before).isEqualTo(StockStatus.AVAILABLE);
                assertThat(emptied.available().value()).isEqualTo(24);
                assertThat(lost.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            }
        }

        @Test
        @DisplayName("L4: a LOST carton recorded in a QUARANTINE area comes back QUARANTINE, out of ATP")
        void foundCartonInQuarantineStaysInQuarantine() {
            when(locations.isQuarantineArea(new LocationId("HCM-A01-1-A"))).thenReturn(true);
            StockPackage lost = cartonWith(id, StockPackageStatus.LOST, 24);
            StockItem emptied = rowIn(id, 0, StockStatus.AVAILABLE);
            rowsOf(lost, emptied);

            contents.adjust(id, 24, false, NOW);

            assertThat(emptied.status()).isEqualTo(StockStatus.QUARANTINE);
            assertThat(emptied.onHand().value()).isEqualTo(24);
            assertThat(emptied.available().value()).as("not sellable until released").isZero();
        }

        @Test
        @DisplayName("a LOST carton counted in at another figure than its standard (history) is not found again")
        void offStandardLostCartonStaysLost() {
            StockPackage lost = new StockPackage(id, "LPN-0001", UUID.randomUUID(), UUID.randomUUID(), 24, 20,
                    new LocationId("HCM-A01-1-A"), StockPackageStatus.LOST, UUID.randomUUID(), NOW, 0L);
            rowsOf(lost, row(id, 0));

            assertThat(codeOf(() -> contents.adjust(id, 24, false, NOW)))
                    .isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
        }

        @Test
        @DisplayName("a LOST carton is found whole or not at all: part of it is STOCK_PACKAGE_TAKEN_WHOLE, still LOST")
        void lostCartonIsNotFoundInPart() {
            StockPackage lost = cartonWith(id, StockPackageStatus.LOST, 24);
            StockItem emptied = row(id, 0);
            rowsOf(lost, emptied);

            BusinessException refused = catchThrowableOfType(BusinessException.class,
                    () -> contents.adjust(id, 20, false, NOW));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(lost.status()).isEqualTo(StockPackageStatus.LOST);
            assertThat(emptied.onHand().value()).isZero();
        }

        @Test
        @DisplayName("a consumed or void carton takes nothing back")
        void closedCartonTakesNothing() {
            rowsOf(cartonWith(id, StockPackageStatus.CONSUMED, 24), row(id, 0));

            BusinessException refused = catchThrowableOfType(BusinessException.class,
                    () -> contents.adjust(id, 24, false, NOW));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
        }
    }
}
