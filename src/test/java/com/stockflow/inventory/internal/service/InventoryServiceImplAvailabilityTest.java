package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.inventory.api.InventoryControlService;
import com.stockflow.inventory.api.InventoryPolicy;
import com.stockflow.inventory.api.LowStockItem;
import com.stockflow.inventory.api.RemovalStrategy;
import com.stockflow.inventory.api.ReserveProductionStockCommand;
import com.stockflow.inventory.api.ReserveStockResult;
import com.stockflow.inventory.api.StockAvailability;
import com.stockflow.inventory.api.StockLevel;
import com.stockflow.inventory.api.TrackingMode;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.InsufficientStockException;
import com.stockflow.inventory.internal.domain.InventoryItemDirectory;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.SkuReservationRepository;
import com.stockflow.inventory.internal.domain.StockAllocator;
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
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository.ThresholdRow;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static com.stockflow.inventory.internal.service.InventoryFixtures.TODAY;
import static com.stockflow.inventory.internal.service.InventoryFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Available-to-promise (kltn-docs 13/14: ATP per SKU and per warehouse), levels, low stock and production holds. */
class InventoryServiceImplAvailabilityTest {

    private static final Sku CUP = new Sku("CUP-12OZ");
    private static final UUID PRODUCTION_ORDER = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();

    private final StockItemRepository repository = mock(StockItemRepository.class);
    private final StockOperations operations = mock(StockOperations.class);
    private final InventoryControlService controls = mock(InventoryControlService.class);
    private final InventoryPolicyRepository policies = mock(InventoryPolicyRepository.class);
    private final SkuReservationRepository skuReservations = mock(SkuReservationRepository.class);
    private final CycleCountRepository cycleCounts = mock(CycleCountRepository.class);
    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final InventoryServiceImpl service = new InventoryServiceImpl(repository,
            new InventoryEventPublisher(mock(ApplicationEventPublisher.class)), operations,
            mock(InventoryItemDirectory.class), Clock.fixed(NOW, ZoneOffset.UTC), controls, policies, skuReservations,
            mock(LocationDirectory.class), cycleCounts, stockPackages,
            mock(com.stockflow.inventory.internal.domain.StockDamageReportRepository.class), Duration.ofMinutes(30));

    private final Map<StockItemId, StockItem> rows = new HashMap<>();

    @BeforeEach
    void defaults() {
        when(controls.policy(any())).thenReturn(new InventoryPolicy(null, null, null, RemovalStrategy.FEFO,
                TrackingMode.LOT, false, null));
        when(repository.findByIdForUpdate(any())).thenAnswer(c -> Optional.ofNullable(rows.get(c.<StockItemId>getArgument(0))));
        when(repository.findById(any())).thenAnswer(c -> Optional.ofNullable(rows.get(c.<StockItemId>getArgument(0))));
    }

    private StockItem stock(String location, String lot, LocalDate expiry, int onHand) {
        StockItem item = InventoryFixtures.available(CUP.code(), location, lot, expiry, onHand);
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
                controls, policies, skuReservations, null, cycleCounts, stockPackages, null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InventoryServiceImpl(repository, null, operations, null, Clock.systemUTC(),
                controls, policies, skuReservations, null, cycleCounts, stockPackages, null, Duration.ofMinutes(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("available to promise")
    class Atp {

        @Test
        @DisplayName("is unheld sellable stock minus live SKU reservations minus units in doubt, never below zero")
        void netFigure() {
            when(repository.availableQuantities(any(), any())).thenReturn(Map.of("CUP-12OZ", 100L, "LID-12OZ", 10L));
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 30L, "LID-12OZ", 50L));
            when(cycleCounts.frozen(anyCollection(), any())).thenReturn(List.of(
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
            verify(repository, never()).availableQuantities(any(), any());
        }

        @Test
        @DisplayName("the stock expiry date is judged against today in Vietnam's calendar")
        void todayIsBusinessDate() {
            service.availableQuantities(Set.of("CUP-12OZ"));

            verify(repository).availableQuantities(Set.of("CUP-12OZ"), TODAY);
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
            when(repository.sumAvailableBySkus(anyCollection(), any())).thenReturn(Map.of(CUP, 100));
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 30L));
            when(cycleCounts.frozen(anyCollection(), any())).thenReturn(List.of(
                    new CycleCountRepository.Frozen(UUID.randomUUID(), "CUP-12OZ", "HCM-A01-1-A", 20)));

            Map<Sku, Integer> atp = service.availableToPromise(List.of(CUP, lid, CUP));

            assertThat(atp).containsEntry(CUP, 50).containsEntry(lid, 0).hasSize(2);
        }

        @Test
        @DisplayName("for a list, reservations above the stock leave 0, not a negative figure")
        void listNeverNegative() {
            when(repository.sumAvailableBySkus(anyCollection(), any())).thenReturn(Map.of(CUP, 10));
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 25L));

            assertThat(service.availableToPromise(List.of(CUP))).containsEntry(CUP, 0);
        }

        @Test
        @DisplayName("per warehouse, the figure of one warehouse's own levels")
        void perWarehouse() {
            when(repository.findLevelLinesBySku(CUP)).thenReturn(List.of(
                    new StockLevelLine(new LocationId("HCM-A01"), StockStatus.AVAILABLE, 40, 10, null),
                    new StockLevelLine(new LocationId("HN-A01"), StockStatus.AVAILABLE, 25, 0, null)));

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
                    new StockLevelLine(new LocationId("HCM-A01"), StockStatus.AVAILABLE, 40, 0, null),
                    new StockLevelLine(new LocationId("HN-A01"), StockStatus.AVAILABLE, 30, 0, null)));
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
                    new StockLevelLine(new LocationId("HCM-A01"), StockStatus.AVAILABLE, 40, 0, null),
                    new StockLevelLine(new LocationId("HN-A01"), StockStatus.AVAILABLE, 30, 0, null)));
            when(cycleCounts.frozen(anyCollection(), any())).thenReturn(List.of(
                    new CycleCountRepository.Frozen(UUID.randomUUID(), "CUP-12OZ", "HCM-A01", 15)));

            List<StockLevel> levels = service.levelsOf(CUP);

            assertThat(levels).extracting(StockLevel::warehouseCode, StockLevel::atp)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("HCM", 25),
                            org.assertj.core.groups.Tuple.tuple("HN", 30));
        }

        @Test
        @DisplayName("quarantined and expired stock count on hand but promise nothing")
        void unsellableStockPromisesNothing() {
            when(repository.findLevelLinesBySku(CUP)).thenReturn(List.of(
                    new StockLevelLine(new LocationId("HCM-A01"), StockStatus.QUARANTINE, 40, 0, null),
                    new StockLevelLine(new LocationId("HCM-A02"), StockStatus.AVAILABLE, 30, 0, TODAY.minusDays(1)),
                    new StockLevelLine(new LocationId("HCM-A03"), StockStatus.AVAILABLE, 10, 0, TODAY)));

            StockLevel level = service.levelsOf(CUP).get(0);

            assertThat(level.onHand()).isEqualTo(80);
            assertThat(level.atp()).isEqualTo(10);
        }

        @Test
        @DisplayName("BR-SEC-002: a warehouse-bound caller sees only their own warehouses' levels")
        void scoped() {
            when(repository.findLevelLinesBySku(CUP)).thenReturn(List.of(
                    new StockLevelLine(new LocationId("HCM-A01"), StockStatus.AVAILABLE, 40, 0, null),
                    new StockLevelLine(new LocationId("HN-A01"), StockStatus.AVAILABLE, 30, 0, null)));

            WithCurrentUser.run(clerkOf("HN"), () ->
                    assertThat(service.levelsOf(CUP)).extracting(StockLevel::warehouseCode).containsExactly("HN"));
        }
    }

    @Nested
    @DisplayName("availability of a SKU by bin")
    class Availability {

        @Test
        @DisplayName("rows come in the SKU's removal order: FEFO - the earlier expiry first")
        void fefoOrder() {
            StockItem later = stock("HCM-A01-1-A", "L2", TODAY.plusDays(90), 10);
            StockItem sooner = stock("HCM-A02-1-A", "L1", TODAY.plusDays(10), 10);
            when(repository.findAvailableBySku(CUP)).thenReturn(List.of(later, sooner));

            assertThat(service.availabilityOf(CUP)).extracting(StockAvailability::lotNumber).containsExactly("L1", "L2");
        }

        @Test
        @DisplayName("FIFO: the older receipt first")
        void fifoOrder() {
            when(controls.policy(any())).thenReturn(new InventoryPolicy(null, null, null, RemovalStrategy.FIFO,
                    TrackingMode.LOT, false, null));
            StockItem newer = InventoryFixtures.availableReceived("CUP-12OZ", "HCM-A01-1-A", "L2", null, 10,
                    NOW.minusSeconds(60));
            StockItem older = InventoryFixtures.availableReceived("CUP-12OZ", "HCM-A02-1-A", "L1", null, 10,
                    NOW.minusSeconds(3600));
            when(repository.findAvailableBySku(CUP)).thenReturn(List.of(newer, older));

            assertThat(service.availabilityOf(CUP)).extracting(StockAvailability::lotNumber).containsExactly("L1", "L2");
        }

        @Test
        @DisplayName("expired stock and layers in doubt are left out")
        void expiredAndFrozenLeftOut() {
            StockItem expired = stock("HCM-A01-1-A", "L0", TODAY.minusDays(1), 10);
            StockItem doubtful = stock("HCM-A02-1-A", "L1", null, 10);
            StockItem fine = stock("HCM-A03-1-A", "L2", TODAY, 10);
            when(repository.findAvailableBySku(CUP)).thenReturn(List.of(expired, doubtful, fine));
            when(cycleCounts.frozen(anyCollection(), any())).thenReturn(List.of(
                    new CycleCountRepository.Frozen(doubtful.id().value(), "CUP-12OZ", "HCM-A02-1-A", 10)));

            assertThat(service.availabilityOf(CUP)).extracting(StockAvailability::lotNumber).containsExactly("L2");
        }

        @Test
        @DisplayName("BR-SEC-002: a warehouse-bound caller sees the bins of their own warehouses only")
        void scoped() {
            when(repository.findAvailableBySku(CUP)).thenReturn(List.of(stock("HCM-A01-1-A", "L1", null, 10),
                    stock("HN-A01-1-A", "L2", null, 10)));

            WithCurrentUser.run(clerkOf("HN"), () ->
                    assertThat(service.availabilityOf(CUP)).extracting(StockAvailability::locationCode)
                            .containsExactly("HN-A01-1-A"));
        }

        @Test
        @DisplayName("each row shows on hand, reserved and available separately")
        void rowFigures() {
            StockItem bin = stock("HCM-A01-1-A", "L1", null, 50);
            bin.reserve(ORDER, UUID.randomUUID(), UUID.randomUUID(), Quantity.of(12), NOW);
            when(repository.findAvailableBySku(CUP)).thenReturn(List.of(bin));

            StockAvailability row = service.availabilityOf(CUP).get(0);

            assertThat(row.onHand()).isEqualTo(50);
            assertThat(row.reserved()).isEqualTo(12);
            assertThat(row.available()).isEqualTo(38);
        }
    }

    @Nested
    @DisplayName("low stock scan")
    class LowStock {

        @Test
        @DisplayName("a limit under 1 is refused")
        void limitMustBePositive() {
            assertThatThrownBy(() -> service.lowStockBatch(null, 0)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("only SKUs at or under their reorder point, or below safety stock, are returned, with both flags")
        void onlyLow() {
            when(policies.thresholdPage(eq(""), anyInt(), any())).thenReturn(List.of(
                    new ThresholdRow("A-OK", 100, 20, 5),
                    new ThresholdRow("B-REORDER", 20, 20, 5),
                    new ThresholdRow("C-SAFETY", 3, null, 5)));

            List<LowStockItem> low = service.lowStockBatch(null, 10);

            assertThat(low).extracting(LowStockItem::sku).containsExactly("B-REORDER", "C-SAFETY");
            assertThat(low.get(0).reorderRequired()).isTrue();
            assertThat(low.get(0).belowSafetyStock()).isFalse();
            assertThat(low.get(1).reorderRequired()).isFalse();
            assertThat(low.get(1).belowSafetyStock()).isTrue();
        }

        @Test
        @DisplayName("stops at the limit")
        void stopsAtLimit() {
            when(policies.thresholdPage(eq("M"), anyInt(), any())).thenReturn(List.of(
                    new ThresholdRow("N1", 1, 20, null), new ThresholdRow("N2", 1, 20, null),
                    new ThresholdRow("N3", 1, 20, null)));

            assertThat(service.lowStockBatch("M", 2)).extracting(LowStockItem::sku).containsExactly("N1", "N2");
        }

        @Test
        @DisplayName("keeps reading pages until enough are low")
        void readsFurtherPages() {
            List<ThresholdRow> fullPageNothingLow = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                fullPageNothingLow.add(new ThresholdRow("S%04d".formatted(i), 100, 20, null));
            }
            when(policies.thresholdPage(eq(""), anyInt(), any())).thenReturn(fullPageNothingLow);
            when(policies.thresholdPage(eq("S0199"), anyInt(), any())).thenReturn(List.of(
                    new ThresholdRow("T0001", 1, 20, null)));

            assertThat(service.lowStockBatch(null, 5)).extracting(LowStockItem::sku).containsExactly("T0001");
        }
    }

    @Nested
    @DisplayName("holds for production orders")
    class ProductionHolds {

        @Test
        @DisplayName("a production hold needs the whole SKU's ATP, checkout reservations included")
        void needsSystemWideAtp() {
            when(repository.availableQuantities(any(), any())).thenReturn(Map.of("CUP-12OZ", 100L));
            when(skuReservations.heldBySku(anyCollection())).thenReturn(Map.of("CUP-12OZ", 90L));

            InsufficientStockException refused = catchThrowableOfType(InsufficientStockException.class,
                    () -> service.reserveForProduction(new ReserveProductionStockCommand(UUID.randomUUID(), CUP, 11,
                            PRODUCTION_ORDER, "HCM")));

            assertThat(refused.available()).isEqualTo(10);
            verify(policies).lock("CUP-12OZ");
        }

        @Test
        @DisplayName("holds are placed on the production order's own warehouse only, in id order, FEFO planned")
        void holdsInTheProductionWarehouse() {
            StockItem local = stock("HCM-A01-1-A", "L1", TODAY.plusDays(20), 30);
            StockItem elsewhere = stock("HN-A01-1-A", "L0", TODAY.plusDays(5), 500);
            when(repository.availableQuantities(any(), any())).thenReturn(Map.of("CUP-12OZ", 530L));
            when(repository.findAvailabilityBySku(CUP)).thenReturn(List.of(local, elsewhere).stream().map(item ->
                    new StockAllocator.Candidate(item.id(), item.location(), item.lotNumber(), item.expiryDate(),
                            item.status(), item.available(), item.receivedAt())).toList());

            ReserveStockResult result = service.reserveForProduction(new ReserveProductionStockCommand(
                    UUID.randomUUID(), CUP, 30, PRODUCTION_ORDER, "hcm"));

            assertThat(result.quantityReserved()).isEqualTo(30);
            assertThat(local.reserved().value()).isEqualTo(30);
            assertThat(elsewhere.reserved().value()).isZero();
            assertThat(local.reservations().get(0).productionOrderId()).isEqualTo(PRODUCTION_ORDER);
            assertThat(result.orderId()).isNull();
            verify(repository).save(local);
        }

        @Test
        @DisplayName("a warehouse that cannot cover the quantity is INSUFFICIENT_STOCK even when the system as a whole can")
        void warehouseShort() {
            StockItem local = stock("HCM-A01-1-A", "L1", null, 10);
            when(repository.availableQuantities(any(), any())).thenReturn(Map.of("CUP-12OZ", 510L));
            when(repository.findAvailabilityBySku(CUP)).thenReturn(List.of(new StockAllocator.Candidate(local.id(),
                    local.location(), "L1", null, local.status(), local.available(), null)));

            assertThat(codeOf(() -> service.reserveForProduction(new ReserveProductionStockCommand(UUID.randomUUID(),
                    CUP, 30, PRODUCTION_ORDER, "HCM")))).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
        }

        @Test
        @DisplayName("cancelling the production order releases the blanks it held and returns the units released")
        void releaseHolds() {
            StockItem bin = stock("HCM-A01-1-A", "L1", null, 100);
            bin.reserveForProduction(PRODUCTION_ORDER, UUID.randomUUID(), UUID.randomUUID(), Quantity.of(25), NOW);
            when(repository.findWithReservationsForProductionOrder(PRODUCTION_ORDER)).thenReturn(List.of(bin));

            int released = service.releaseProductionHolds(PRODUCTION_ORDER);

            assertThat(released).isEqualTo(25);
            assertThat(bin.reserved().value()).isZero();
            assertThat(bin.reservations().get(0).releaseReason()).isEqualTo(ReleaseReason.PRODUCTION_CANCELLED);
            verify(repository).save(bin);
        }

        @Test
        @DisplayName("blanks already issued to the print shop are closed by production itself, not released here")
        void issuedBlanksAreLeftAlone() {
            StockItem printShop = new StockItem(StockItemId.newId(), CUP, new LocationId("HCM-PRD01"), null, null,
                    Quantity.of(25), StockStatus.IN_PRODUCTION, List.of(), 0L);
            when(repository.findWithReservationsForProductionOrder(PRODUCTION_ORDER)).thenReturn(List.of(printShop));

            assertThat(service.releaseProductionHolds(PRODUCTION_ORDER)).isZero();
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("a hold handed to its order: same hold, the order's now")
        void handHoldToOrder() {
            StockItem bin = stock("HCM-A01-1-A", "L1", null, 100);
            Reservation hold = bin.reserveForProduction(PRODUCTION_ORDER, UUID.randomUUID(), UUID.randomUUID(),
                    Quantity.of(25), NOW);
            when(repository.findByReservationIdForUpdate(hold.id())).thenReturn(Optional.of(bin));

            service.handHoldToOrder(hold.id().value(), PRODUCTION_ORDER, ORDER);

            assertThat(hold.orderId()).isEqualTo(ORDER);
            verify(repository).save(bin);
        }

        @Test
        @DisplayName("handing it over again is a no-op")
        void handHoldIsIdempotent() {
            StockItem bin = stock("HCM-A01-1-A", "L1", null, 100);
            Reservation hold = bin.reserveForProduction(PRODUCTION_ORDER, UUID.randomUUID(), UUID.randomUUID(),
                    Quantity.of(25), NOW);
            bin.handHoldToOrder(hold.id(), PRODUCTION_ORDER, ORDER);
            when(repository.findByReservationIdForUpdate(hold.id())).thenReturn(Optional.of(bin));

            service.handHoldToOrder(hold.id().value(), PRODUCTION_ORDER, ORDER);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("a hold of another production order cannot be handed over")
        void handHoldOfAnotherProductionOrder() {
            StockItem bin = stock("HCM-A01-1-A", "L1", null, 100);
            Reservation hold = bin.reserveForProduction(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    Quantity.of(25), NOW);
            when(repository.findByReservationIdForUpdate(hold.id())).thenReturn(Optional.of(bin));

            assertThat(codeOf(() -> service.handHoldToOrder(hold.id().value(), PRODUCTION_ORDER, ORDER)))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_ISSUABLE);
        }

        @Test
        @DisplayName("a subcontracted part: the hold gives up only the units asked and keeps the rest")
        void reduceHold() {
            StockItem bin = stock("HCM-A01-1-A", "L1", null, 100);
            Reservation hold = bin.reserveForProduction(PRODUCTION_ORDER, UUID.randomUUID(), UUID.randomUUID(),
                    Quantity.of(25), NOW);
            when(repository.findByReservationIdForUpdate(hold.id())).thenReturn(Optional.of(bin));

            service.reduceHold(hold.id().value(), null, PRODUCTION_ORDER, 10);

            assertThat(bin.reserved().value()).isEqualTo(15);
            verify(repository).save(bin);
        }

        @Test
        @DisplayName("a hold cannot give up more than it holds, or nothing, or when it is not this production order's")
        void reduceHoldRefusals() {
            StockItem bin = stock("HCM-A01-1-A", "L1", null, 100);
            Reservation hold = bin.reserveForProduction(PRODUCTION_ORDER, UUID.randomUUID(), UUID.randomUUID(),
                    Quantity.of(25), NOW);
            when(repository.findByReservationIdForUpdate(hold.id())).thenReturn(Optional.of(bin));

            assertThat(codeOf(() -> service.reduceHold(hold.id().value(), null, PRODUCTION_ORDER, 26)))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_ISSUABLE);
            assertThat(codeOf(() -> service.reduceHold(hold.id().value(), null, PRODUCTION_ORDER, 0)))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_ISSUABLE);
            assertThat(codeOf(() -> service.reduceHold(hold.id().value(), null, UUID.randomUUID(), 5)))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_ISSUABLE);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("live holds: only those still active are listed, each once, with their bin and lot")
        void liveHolds() {
            StockItem bin = stock("HCM-A01-1-A", "L1", TODAY.plusDays(9), 100);
            Reservation live = bin.reserveForProduction(PRODUCTION_ORDER, UUID.randomUUID(), UUID.randomUUID(),
                    Quantity.of(25), NOW);
            Reservation closed = bin.reserveForProduction(PRODUCTION_ORDER, UUID.randomUUID(), UUID.randomUUID(),
                    Quantity.of(5), NOW);
            bin.releaseReservation(closed.id(), ReleaseReason.PRODUCTION_CANCELLED, NOW);
            when(repository.findIdByReservationId(live.id())).thenReturn(Optional.of(bin.id()));
            when(repository.findIdByReservationId(closed.id())).thenReturn(Optional.of(bin.id()));

            var holds = service.liveHolds(List.of(live.id().value(), closed.id().value(), live.id().value()));

            assertThat(holds).singleElement().satisfies(hold -> {
                assertThat(hold.reservationId()).isEqualTo(live.id().value());
                assertThat(hold.quantity()).isEqualTo(25);
                assertThat(hold.locationCode()).isEqualTo("HCM-A01-1-A");
            });
            assertThat(live.status()).isEqualTo(ReservationStatus.HELD);
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
