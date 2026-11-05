package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.InsufficientStockException;
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
 * SCRUM-473 / SCRUM-164: a carton stays true to its rows. Taking units from a carton opens it, or closes it CONSUMED
 * when nothing is left; an approved adjustment writes the carton up or down as a whole.
 */
class StockPackageContentsTest {

    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final StockItemRepository stockItems = mock(StockItemRepository.class);
    private final StockPackageContents contents = new StockPackageContents(stockPackages, stockItems);

    private static StockPackage carton(StockPackageId id, boolean unopened, int counted) {
        return new StockPackage(id, "LPN-0001", UUID.randomUUID(), UUID.randomUUID(), 24, counted,
                new LocationId("HCM-A01-1-A"), StockPackageStatus.IN_STOCK, unopened, UUID.randomUUID(), null, 0L);
    }

    private static StockPackage cartonWith(StockPackageId id, StockPackageStatus status, boolean unopened,
                                           int counted) {
        Instant closed = status.isClosed() ? NOW : null;
        return new StockPackage(id, "LPN-0001", UUID.randomUUID(), UUID.randomUUID(), 24, counted,
                new LocationId("HCM-A01-1-A"), status, unopened, UUID.randomUUID(), closed, 0L);
    }

    private static StockItem row(StockPackageId carton, int onHand) {
        return new StockItem(StockItemId.newId(), new Sku("CUP-12OZ"), new LocationId("HCM-A01-1-A"), "L1", null,
                Quantity.of(onHand), StockStatus.AVAILABLE, List.of(), 0L, NOW, null, carton);
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
        @DisplayName("a carton taken from but not emptied is opened")
        void partiallyTakenIsOpened() {
            StockPackageId id = new StockPackageId(UUID.randomUUID());
            StockPackage carton = carton(id, true, 24);
            StockItem row = row(id, 24);
            when(stockPackages.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(carton));
            when(stockItems.onHandInStockPackage(id)).thenReturn(14);

            StockPackageContents.LockedCartons locked = contents.lockFor(List.of(row));
            locked.tookFrom(row);
            contents.settle(locked, NOW);

            assertThat(carton.unopened()).isFalse();
            assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            verify(stockPackages).save(carton);
        }

        @Test
        @DisplayName("a carton emptied by the take is closed CONSUMED at that time")
        void emptiedIsConsumed() {
            StockPackageId id = new StockPackageId(UUID.randomUUID());
            StockPackage carton = carton(id, true, 24);
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
            StockPackage carton = carton(id, true, 24);
            when(stockPackages.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(carton));

            StockPackageContents.LockedCartons locked = contents.lockFor(List.of(row(id, 24)));
            contents.settle(locked, NOW);

            assertThat(carton.unopened()).isTrue();
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
            StockPackage carton = carton(id, true, 24);
            StockItem a = row(id, 10);
            StockItem b = row(id, 14);
            when(stockPackages.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(carton));
            when(stockItems.onHandInStockPackage(id)).thenReturn(5);

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
            rowsOf(carton(id, false, 24));

            assertThatThrownBy(() -> contents.adjust(id, -1, false, NOW)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no stock row");
        }

        @Test
        @DisplayName("written down: units come from its rows in id order; the carton is opened, and one change per row")
        void writeDown() {
            StockPackage carton = carton(id, true, 24);
            StockItem a = row(id, 10);
            StockItem b = row(id, 14);
            rowsOf(carton, a, b);

            List<StockPackageContents.RowChange> changes = contents.adjust(id, -12, false, NOW);

            int taken = changes.stream().mapToInt(StockPackageContents.RowChange::delta).sum();
            assertThat(taken).isEqualTo(-12);
            assertThat(a.onHand().value() + b.onHand().value()).isEqualTo(12);
            assertThat(carton.unopened()).isFalse();
            assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            verify(stockPackages).save(carton);
            verify(stockItems, org.mockito.Mockito.atLeastOnce()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("written down to nothing: CONSUMED, or LOST when the count found nothing")
        void writeDownToNothing() {
            StockPackage consumed = carton(id, true, 24);
            rowsOf(consumed, row(id, 24));
            contents.adjust(id, -24, false, NOW);
            assertThat(consumed.status()).isEqualTo(StockPackageStatus.CONSUMED);

            StockPackage lost = carton(id, true, 24);
            rowsOf(lost, row(id, 24));
            contents.adjust(id, -24, true, NOW);
            assertThat(lost.status()).isEqualTo(StockPackageStatus.LOST);
        }

        @Test
        @DisplayName("a write-down that would cut into a hold is refused whole: a customer's hold is never cancelled by a correction")
        void writeDownCannotCutIntoHolds() {
            StockPackage carton = carton(id, true, 24);
            StockItem held = row(id, 24);
            held.reserve(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Quantity.of(20), NOW);
            rowsOf(carton, held);

            InsufficientStockException refused = catchThrowableOfType(InsufficientStockException.class,
                    () -> contents.adjust(id, -10, false, NOW));

            assertThat(refused.requested()).isEqualTo(10);
            assertThat(refused.available()).isEqualTo(4);
            assertThat(held.onHand().value()).isEqualTo(24);
            verify(stockPackages, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("only an IN_STOCK carton can be written down")
        void writeDownNeedsInStock() {
            rowsOf(cartonWith(id, StockPackageStatus.CONSUMED, false, 24), row(id, 0));

            assertThat(codeOf(() -> contents.adjust(id, -1, false, NOW)))
                    .isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
        }

        @Test
        @DisplayName("an unopened carton holds exactly what was counted into it: a write-up overfills it")
        void writeUpOfUnopenedIsOverfill() {
            rowsOf(carton(id, true, 24), row(id, 24));

            assertThat(codeOf(() -> contents.adjust(id, 1, false, NOW))).isEqualTo(ErrorCode.STOCK_PACKAGE_OVERFILLED);
        }

        @Test
        @DisplayName("an opened carton can be written up, up to what was counted into it - never above")
        void writeUpUpToCounted() {
            StockPackage carton = carton(id, false, 24);
            StockItem remaining = row(id, 20);
            rowsOf(carton, remaining);

            List<StockPackageContents.RowChange> changes = contents.adjust(id, 4, false, NOW);

            assertThat(remaining.onHand().value()).isEqualTo(24);
            assertThat(changes).singleElement().satisfies(c -> assertThat(c.delta()).isEqualTo(4));
            verify(stockItems).saveMerged(remaining);
            assertThat(codeOf(() -> {
                rowsOf(carton(id, false, 24), row(id, 24));
                contents.adjust(id, 1, false, NOW);
            })).isEqualTo(ErrorCode.STOCK_PACKAGE_OVERFILLED);
        }

        @Test
        @DisplayName("a LOST carton that is found goes back IN_STOCK on its first row")
        void lostCartonIsFound() {
            StockPackage lost = cartonWith(id, StockPackageStatus.LOST, false, 24);
            StockItem emptied = row(id, 0);
            rowsOf(lost, emptied);

            contents.adjust(id, 24, false, NOW);

            assertThat(lost.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(emptied.onHand().value()).isEqualTo(24);
        }

        @Test
        @DisplayName("a consumed or void carton takes nothing back")
        void closedCartonTakesNothing() {
            rowsOf(cartonWith(id, StockPackageStatus.CONSUMED, false, 24), row(id, 0));

            BusinessException refused = catchThrowableOfType(BusinessException.class,
                    () -> contents.adjust(id, 5, false, NOW));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
        }
    }
}
