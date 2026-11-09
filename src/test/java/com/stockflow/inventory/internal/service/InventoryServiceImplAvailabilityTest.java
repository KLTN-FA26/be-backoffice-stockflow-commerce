package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.StockAvailability;
import com.stockflow.inventory.api.StockLevel;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.InventoryItemDirectory;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.SkuReservationRepository;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockLevelLine;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Available-to-promise (kltn-docs 13/14: ATP per SKU and per warehouse), levels and low stock. */
class InventoryServiceImplAvailabilityTest {

    private static final Sku CUP = new Sku("CUP-12OZ");
    private static final UUID ORDER = UUID.randomUUID();

    private final StockItemRepository repository = mock(StockItemRepository.class);
    private final StockOperations operations = mock(StockOperations.class);
    private final InventoryPolicyRepository policies = mock(InventoryPolicyRepository.class);
    private final SkuReservationRepository skuReservations = mock(SkuReservationRepository.class);
    private final CycleCountRepository cycleCounts = mock(CycleCountRepository.class);
    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final InventoryServiceImpl service = new InventoryServiceImpl(repository,
            new InventoryEventPublisher(mock(ApplicationEventPublisher.class)), operations,
            mock(InventoryItemDirectory.class), Clock.fixed(NOW, ZoneOffset.UTC), policies, skuReservations,
            mock(LocationDirectory.class), cycleCounts, stockPackages,
            mock(com.stockflow.inventory.internal.domain.StockDamageReportRepository.class), Duration.ofMinutes(30));

    private final Map<StockItemId, StockItem> rows = new HashMap<>();

    @BeforeEach
    void defaults() {
        when(repository.findByIdForUpdate(any())).thenAnswer(c -> Optional.ofNullable(rows.get(c.<StockItemId>getArgument(0))));
        when(repository.findById(any())).thenAnswer(c -> Optional.ofNullable(rows.get(c.<StockItemId>getArgument(0))));
    }

    private StockItem stock(String location, String lot, int onHand) {
        StockItem item = InventoryFixtures.available(CUP.code(), location, lot, onHand);
        rows.put(item.id(), item);
        return item;
    }

    private static CurrentUser clerkOf(String prefix) {
        return new CurrentUser(UUID.randomUUID(), "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(), DataScope.WAREHOUSE,
                Set.of(prefix), Set.of(UUID.randomUUID()));
    }

    @Test
    @DisplayName("a reservation TTL that is not positive is refused at construction")
    void ttlMustBePositive() {
        assertThatThrownBy(() -> new InventoryServiceImpl(repository, null, operations, null, Clock.systemUTC(),
                policies, skuReservations, null, cycleCounts, stockPackages, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InventoryServiceImpl(repository, null, operations, null, Clock.systemUTC(),
                policies, skuReservations, null, cycleCounts, stockPackages, null, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("available to promise")
    class Atp {

        @Test
        @DisplayName("is unheld sellable stock minus live SKU reservations minus units in doubt, never below zero")
        void netFigure() {
            when(repository.availableQuantities(any())).thenReturn(Map.of("CUP-12OZ", 100L, "LID-12OZ", 10L));
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 30L, "LID-12OZ", 50L));
            when(cycleCounts.frozen(anyCollection())).thenReturn(List.of(
                    new CycleCountRepository.Frozen(UUID.randomUUID(), "CUP-12OZ", "HCM-A01-1-A", 5),
                    new CycleCountRepository.Frozen(UUID.randomUUID(), "CUP-12OZ", "HCM-A02-1-A", 15)));

            Map<String, Long> atp = service.availableQuantities(Set.of("cup-12oz", "LID-12OZ"));

            assertThat(atp).containsEntry("CUP-12OZ", 50L);
            assertThat(atp).doesNotContainKey("LID-12OZ");
        }

        @Test
        @DisplayName("asking for no SKU reads nothing")
        void noSkus() {
            assertThat(service.availableQuantities(Set.of())).isEmpty();
            verify(repository, never()).availableQuantities(any());
        }

        @Test
        @DisplayName("the SKUs asked for are read in one query")
        void oneQuery() {
            service.availableQuantities(Set.of("CUP-12OZ"));

            verify(repository).availableQuantities(Set.of("CUP-12OZ"));
        }

        @Test
        @DisplayName("a single SKU with no stock is 0, not an error")
        void singleSkuWithNoStock() {
            assertThat(service.availableToPromise(CUP)).isZero();
        }

        @Test
        @DisplayName("for a list, every SKU asked for gets an answer: no stock is 0, and reservations and doubt reduce it")
        void listAnswersEverySku() {
            Sku lid = new Sku("LID-12OZ");
            when(repository.sumAvailableBySkus(anyCollection())).thenReturn(Map.of(CUP, 100));
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 30L));
            when(cycleCounts.frozen(anyCollection())).thenReturn(List.of(
                    new CycleCountRepository.Frozen(UUID.randomUUID(), "CUP-12OZ", "HCM-A01-1-A", 20)));

            Map<Sku, Integer> atp = service.availableToPromise(List.of(CUP, lid, CUP));

            assertThat(atp).containsEntry(CUP, 50).containsEntry(lid, 0).hasSize(2);
        }

        @Test
        @DisplayName("for a list, reservations above the stock leave 0, not a negative figure")
        void listNeverNegative() {
            when(repository.sumAvailableBySkus(anyCollection())).thenReturn(Map.of(CUP, 10));
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 25L));

            assertThat(service.availableToPromise(List.of(CUP))).containsEntry(CUP, 0);
        }

        @Test
        @DisplayName("per warehouse, the figure of one warehouse's own levels")
        void perWarehouse() {
            when(repository.findLevelLinesBySku(CUP)).thenReturn(List.of(
                    new StockLevelLine(new LocationId("HCM-A01"), StockStatus.AVAILABLE, 40, 10),
                    new StockLevelLine(new LocationId("HN-A01"), StockStatus.AVAILABLE, 25, 0)));

            assertThat(service.availableToPromise(CUP, "hcm")).isEqualTo(30);
            assertThat(service.availableToPromise(CUP, "HN")).isEqualTo(25);
            assertThat(service.availableToPromise(CUP, "DN")).isZero();
        }
    }

    @Nested
    @DisplayName("levels per warehouse")
    class Levels {

        @Test
        @DisplayName("no warehouse promises more than is left system-wide once SKU reservations are counted")
        void capBySystemWideFigure() {
            when(repository.findLevelLinesBySku(CUP)).thenReturn(List.of(
                    new StockLevelLine(new LocationId("HCM-A01"), StockStatus.AVAILABLE, 40, 0),
                    new StockLevelLine(new LocationId("HN-A01"), StockStatus.AVAILABLE, 30, 0)));
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 50L));

            List<StockLevel> levels = service.levelsOf(CUP);

            // 70 unheld system-wide, 50 promised: 20 left, so neither warehouse can promise more than 20.
            assertThat(levels).extracting(StockLevel::warehouseCode, StockLevel::atp)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("HCM", 20),
                            org.assertj.core.groups.Tuple.tuple("HN", 20));
        }

        @Test
        @DisplayName("units in doubt reduce the ATP of their own warehouse only")
        void doubtReducesItsWarehouse() {
            when(repository.findLevelLinesBySku(CUP)).thenReturn(List.of(
                    new StockLevelLine(new LocationId("HCM-A01"), StockStatus.AVAILABLE, 40, 0),
                    new StockLevelLine(new LocationId("HN-A01"), StockStatus.AVAILABLE, 30, 0)));
            when(cycleCounts.frozen(anyCollection())).thenReturn(List.of(
                    new CycleCountRepository.Frozen(UUID.randomUUID(), "CUP-12OZ", "HCM-A01", 15)));

            List<StockLevel> levels = service.levelsOf(CUP);

            assertThat(levels).extracting(StockLevel::warehouseCode, StockLevel::atp)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("HCM", 25),
                            org.assertj.core.groups.Tuple.tuple("HN", 30));
        }

        @Test
        @DisplayName("quarantined stock counts on hand but promises nothing")
        void unsellableStockPromisesNothing() {
            when(repository.findLevelLinesBySku(CUP)).thenReturn(List.of(
                    new StockLevelLine(new LocationId("HCM-A01"), StockStatus.QUARANTINE, 40, 0),
                    new StockLevelLine(new LocationId("HCM-A03"), StockStatus.AVAILABLE, 10, 0)));

            StockLevel level = service.levelsOf(CUP).get(0);

            assertThat(level.onHand()).isEqualTo(50);
            assertThat(level.atp()).isEqualTo(10);
        }

        @Test
        @DisplayName("BR-SEC-002: a warehouse-bound caller sees only their own warehouses' levels")
        void scoped() {
            when(repository.findLevelLinesBySku(CUP)).thenReturn(List.of(
                    new StockLevelLine(new LocationId("HCM-A01"), StockStatus.AVAILABLE, 40, 0),
                    new StockLevelLine(new LocationId("HN-A01"), StockStatus.AVAILABLE, 30, 0)));

            WithCurrentUser.run(clerkOf("HN"), () ->
                    assertThat(service.levelsOf(CUP)).extracting(StockLevel::warehouseCode).containsExactly("HN"));
        }
    }

    @Nested
    @DisplayName("availability of a SKU by bin")
    class Availability {

        @Test
        @DisplayName("FIFO for every SKU: the older receipt first (kltn-docs 01 BR-17)")
        void fifoOrder() {
            StockItem newer = InventoryFixtures.availableReceived("CUP-12OZ", "HCM-A01-1-A", "L2", 10,
                    NOW.minusSeconds(60));
            StockItem older = InventoryFixtures.availableReceived("CUP-12OZ", "HCM-A02-1-A", "L1", 10,
                    NOW.minusSeconds(3600));
            when(repository.findAvailableBySku(CUP)).thenReturn(List.of(newer, older));

            assertThat(service.availabilityOf(CUP)).extracting(StockAvailability::lotNumber).containsExactly("L1", "L2");
        }

        @Test
        @DisplayName("layers in doubt are left out")
        void frozenLeftOut() {
            StockItem doubtful = stock("HCM-A02-1-A", "L1", 10);
            StockItem fine = stock("HCM-A03-1-A", "L2", 10);
            when(repository.findAvailableBySku(CUP)).thenReturn(List.of(doubtful, fine));
            when(cycleCounts.frozen(anyCollection())).thenReturn(List.of(
                    new CycleCountRepository.Frozen(doubtful.id().value(), "CUP-12OZ", "HCM-A02-1-A", 10)));

            assertThat(service.availabilityOf(CUP)).extracting(StockAvailability::lotNumber).containsExactly("L2");
        }

        @Test
        @DisplayName("BR-SEC-002: a warehouse-bound caller sees the bins of their own warehouses only")
        void scoped() {
            when(repository.findAvailableBySku(CUP)).thenReturn(List.of(stock("HCM-A01-1-A", "L1", 10),
                    stock("HN-A01-1-A", "L2", 10)));

            WithCurrentUser.run(clerkOf("HN"), () ->
                    assertThat(service.availabilityOf(CUP)).extracting(StockAvailability::locationCode)
                            .containsExactly("HN-A01-1-A"));
        }

        @Test
        @DisplayName("each row shows on hand, reserved and available separately")
        void rowFigures() {
            StockItem bin = stock("HCM-A01-1-A", "L1", 50);
            bin.reserve(ORDER, UUID.randomUUID(), UUID.randomUUID(), Quantity.of(12), NOW);
            when(repository.findAvailableBySku(CUP)).thenReturn(List.of(bin));

            StockAvailability row = service.availabilityOf(CUP).get(0);

            assertThat(row.onHand()).isEqualTo(50);
            assertThat(row.reserved()).isEqualTo(12);
            assertThat(row.available()).isEqualTo(38);
        }
    }

    @Nested
    @DisplayName("cartons of a receipt")
    class ReceiptCartons {

        private StockPackage carton(StockPackageStatus status, UUID line) {
            Instant closed = status.isClosed() ? NOW : null;
            Integer counted = status == StockPackageStatus.RECEIVING ? null : 24;
            return new StockPackage(new StockPackageId(UUID.randomUUID()), "LPN-" + UUID.randomUUID().toString()
                    .substring(0, 8).toUpperCase(), UUID.randomUUID(), UUID.randomUUID(), 24, counted,
                    new LocationId("HCM-RCV01"), status, true, line, closed, 0L);
        }

        @Test
        @DisplayName("lines that have cartons are those a carton names")
        void linesWithCartons() {
            UUID withCartons = UUID.randomUUID();
            when(stockPackages.findByReceiptLines(anyCollection())).thenReturn(List.of(
                    carton(StockPackageStatus.RECEIVING, withCartons), carton(StockPackageStatus.IN_STOCK, withCartons)));

            assertThat(service.receiptLinesWithStockPackages(List.of(withCartons, UUID.randomUUID())))
                    .containsExactly(withCartons);
        }

        @Test
        @DisplayName("voiding a receipt's cartons cancels the ones still being received and leaves the rest alone")
        void voidsOnlyReceivingCartons() {
            UUID line = UUID.randomUUID();
            StockPackage receiving = carton(StockPackageStatus.RECEIVING, line);
            StockPackage inStock = carton(StockPackageStatus.IN_STOCK, line);
            when(stockPackages.findByReceiptLines(anyCollection())).thenReturn(List.of(receiving, inStock));
            when(stockPackages.findAllByIdForUpdate(anyCollection())).thenReturn(List.of(receiving, inStock));

            service.voidStockPackagesOfReceiptLines(List.of(line));

            assertThat(receiving.status()).isEqualTo(StockPackageStatus.VOID);
            assertThat(receiving.closedAt()).isEqualTo(NOW);
            assertThat(inStock.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            verify(stockPackages).save(receiving);
            verify(stockPackages, never()).save(inStock);
        }
    }
}
