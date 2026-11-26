package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.contracts.StockReservationReleased;
import com.stockflow.inventory.api.AllocateStockCommand;
import com.stockflow.inventory.api.AllocateStockResult;
import com.stockflow.inventory.api.HoldStockCommand;
import com.stockflow.inventory.api.HoldStockResult;
import com.stockflow.inventory.api.OrderHold;
import com.stockflow.inventory.internal.domain.CycleCountRepository;
import com.stockflow.inventory.internal.domain.HeldStock;
import com.stockflow.inventory.internal.domain.InventoryItemDirectory;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.SkuReservation;
import com.stockflow.inventory.internal.domain.SkuReservationRepository;
import com.stockflow.inventory.internal.domain.StockAllocator;
import com.stockflow.inventory.internal.domain.StockLevelLine;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.support.WithCurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.inventory.internal.service.InventoryFixtures.NOW;
import static com.stockflow.inventory.internal.service.InventoryFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 4B-7 / 04c F-FUL step 02: an order of a contract holds whole cartons of a SKU at its shipping warehouse, on no bin
 * and with no deadline; releasing the order to that warehouse allocates bins and lots (FIFO); a hold gives way when
 * its order is cancelled or someone releases it by hand. The checkout reservation with a payment deadline, its pin,
 * re-arm and sweeper are gone (V20261117000300). A hold counts cartons of one count and is promised, covered and
 * allocated from cartons of that count only (ADR-0011, V20261231000500).
 */
class InventoryServiceImplReservationTest {

    private static final Sku CUP = new Sku("CUP-12OZ");
    private static final UUID ORDER = UUID.randomUUID();

    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final StockOperations operations = mock(StockOperations.class);
    private final InventoryItemDirectory itemDirectory = mock(InventoryItemDirectory.class);
    private final InventoryPolicyRepository policies = mock(InventoryPolicyRepository.class);
    private final SkuReservationRepository skuReservations = mock(SkuReservationRepository.class);
    private final LocationDirectory locations = mock(LocationDirectory.class);
    private final CycleCountRepository cycleCounts = mock(CycleCountRepository.class);
    private final StockPackageRepository repository = mock(StockPackageRepository.class);
    private final InventoryServiceImpl service = new InventoryServiceImpl(
            new InventoryEventPublisher(publisher), operations, itemDirectory, Clock.fixed(NOW, ZoneOffset.UTC),
            policies, skuReservations, locations, cycleCounts, repository);

    private final Map<StockPackageId, StockPackage> cartons = new HashMap<>();

    @BeforeEach
    void defaults() {
        // The cartons asked for, in id order, as the adapter locks them.
        when(repository.findAllByIdForUpdate(anyCollection())).thenAnswer(c ->
                c.<java.util.Collection<StockPackageId>>getArgument(0).stream().distinct()
                        .sorted(java.util.Comparator.comparing(StockPackageId::value))
                        .map(cartons::get).filter(java.util.Objects::nonNull).toList());
        when(repository.findByIdForUpdate(any())).thenAnswer(c ->
                Optional.ofNullable(cartons.get(c.<StockPackageId>getArgument(0))));
        when(repository.findById(any())).thenAnswer(c ->
                Optional.ofNullable(cartons.get(c.<StockPackageId>getArgument(0))));
        when(repository.findIdByReservationId(any())).thenAnswer(c -> cartons.values().stream()
                .filter(carton -> carton.findReservation(c.<ReservationId>getArgument(0)).isPresent())
                .map(StockPackage::id).findFirst());
        when(locations.pickable(anyCollection())).thenAnswer(c -> {
            Map<String, UUID> pickable = new HashMap<>();
            c.<java.util.Collection<String>>getArgument(0).stream().filter(code -> !code.contains("-QC"))
                    .forEach(code -> pickable.put(code, UUID.randomUUID()));
            return pickable;
        });
    }

    /** A sellable carton in stock, held by no order: what takes an allocation (kltn-docs 20 BR-14). */
    private StockPackage stock(String location, String lot, int units) {
        return received(location, lot, units, null);
    }

    /** As {@link #stock}, received at {@code receivedAt}: the time FIFO ranks by. */
    private StockPackage received(String location, String lot, int units, Instant receivedAt) {
        StockPackage carton = InventoryFixtures.availableReceived(CUP.code(), location, lot, units, receivedAt);
        cartons.put(carton.id(), carton);
        return carton;
    }

    /** The free cartons the planner is offered: what allocation draws on since team plan 4C-2. */
    private void availabilityIs(StockPackage... free) {
        when(repository.findAvailabilityBySku(CUP)).thenReturn(java.util.Arrays.stream(free).map(carton ->
                new StockAllocator.Candidate(carton.id(), carton.location(), carton.lotNumber(), carton.stockStatus(),
                        carton.nominalQty(), carton.receivedAt())).toList());
    }

    /**
     * The cup's sellable, unheld units at HCM, one whole free carton per argument on a pickable bin: the level line
     * Inventory Level reads and the allocatable rows a warehouse promises from (review C4 P-1, {@link PickableStock}).
     */
    private List<StockPackageRepository.Allocatable> levelAtHcmIs(int... cartons) {
        when(repository.findLevelLinesBySku(CUP)).thenReturn(List.of(new StockLevelLine(
                new LocationId("HCM-A01-1-A"), StockStatus.AVAILABLE, java.util.Arrays.stream(cartons).sum(), 0,
                cartons.length, 0)));
        List<StockPackageRepository.Allocatable> allocatable = java.util.Arrays.stream(cartons)
                .mapToObj(units -> new StockPackageRepository.Allocatable(new StockPackageId(UUID.randomUUID()), CUP.code(), "HCM-A01-1-A",
                        units))
                .toList();
        when(repository.findAllocatable(anyCollection())).thenReturn(allocatable);
        return allocatable;
    }

    /** {@code cartons} cartons of {@code unitsPerCarton} held at HCM. */
    private static SkuReservation hold(String sku, int cartons, int unitsPerCarton) {
        return SkuReservation.holdAt("HCM", new Sku(sku), ORDER, UUID.randomUUID(), cartons, unitsPerCarton, 1000, NOW);
    }

    /** {@code count} cartons of {@code unitsPerCarton}, for {@link #levelAtHcmIs}. */
    private static int[] cartonsOf(int count, int unitsPerCarton) {
        int[] cartons = new int[count];
        java.util.Arrays.fill(cartons, unitsPerCarton);
        return cartons;
    }

    // ------------------------------------------------------------------ hold at the shipping warehouse

    @Nested
    @DisplayName("holding at the order's shipping warehouse (4B-7, 20 BR-17, BR-21)")
    class HoldAtWarehouse {

        private final UUID requestId = UUID.randomUUID();

        private HoldStockResult hold(int cartons, int unitsPerCarton) {
            return service.holdAtWarehouse(new HoldStockCommand(requestId, ORDER, CUP, "hcm", cartons, unitsPerCarton));
        }

        @Test
        @DisplayName("holds whole cartons against the SKU at the warehouse, with no deadline, under the SKU lock, and "
                + "announces it")
        void holdsAtTheWarehouse() {
            levelAtHcmIs(cartonsOf(10, 10));

            HoldStockResult result = hold(3, 10);

            org.mockito.ArgumentCaptor<SkuReservation> saved = org.mockito.ArgumentCaptor.forClass(SkuReservation.class);
            verify(skuReservations).save(saved.capture());
            assertThat(saved.getValue().cartons()).isEqualTo(3);
            assertThat(saved.getValue().unitsPerCarton()).as("the count the order line was quoted in").isEqualTo(10);
            assertThat(saved.getValue().quantity().value()).as("units, derived").isEqualTo(30);
            assertThat(saved.getValue().warehouseCode()).isEqualTo("HCM");
            assertThat(saved.getValue().status()).isEqualTo(ReservationStatus.HELD);
            assertThat(result.cartonsHeld()).isEqualTo(3);
            assertThat(result.warehouseCode()).isEqualTo("HCM");
            assertThat(result.reservationIds()).containsExactly(saved.getValue().id().value());
            verify(policies).lock("CUP-12OZ");
            verify(publisher).publishEvent(any(com.stockflow.contracts.StockReserved.class));
        }

        @Test
        @DisplayName("a shortage holds the whole cartons there are, never part of one, and is no error")
        void shortageHoldsWholeCartons() {
            levelAtHcmIs(10, 10);

            HoldStockResult result = hold(3, 10);

            assertThat(result.cartonsHeld()).isEqualTo(2);
            org.mockito.ArgumentCaptor<SkuReservation> saved = org.mockito.ArgumentCaptor.forClass(SkuReservation.class);
            verify(skuReservations).save(saved.capture());
            assertThat(saved.getValue().cartons()).isEqualTo(2);
            assertThat(saved.getValue().quantity().value()).isEqualTo(20);
        }

        @Test
        @DisplayName("ADR-0011: only cartons of the line's count are promised - a free carton of another count is not, "
                + "though its units would cover the hold")
        void cartonsOfAnotherCountAreNotPromised() {
            // 2 cartons of 10 and 2 of 25 free: 70 units, of which a line quoted in cartons of 10 can take 20.
            levelAtHcmIs(10, 25, 10, 25);

            HoldStockResult result = hold(3, 10);

            assertThat(result.cartonsHeld()).as("the cartons of 25 are not cartons of 10").isEqualTo(2);
            org.mockito.ArgumentCaptor<SkuReservation> saved = org.mockito.ArgumentCaptor.forClass(SkuReservation.class);
            verify(skuReservations).save(saved.capture());
            assertThat(saved.getValue().unitsPerCarton()).isEqualTo(10);
            assertThat(saved.getValue().quantity().value()).isEqualTo(20);
        }

        @Test
        @DisplayName("ADR-0011: nothing of the count at all holds nothing, whatever other cartons stand free")
        void noCartonOfTheCount() {
            levelAtHcmIs(25, 25, 25, 25);

            assertThat(hold(1, 10).cartonsHeld()).isZero();
            verify(skuReservations, never()).save(any());
        }

        @Test
        @DisplayName("ADR-0011: what other orders hold in cartons of another count takes nothing from this count")
        void holdsOfAnotherCountDoNotCompete() {
            levelAtHcmIs(10, 25, 10, 25);
            when(skuReservations.heldCartonsOf("CUP-12OZ")).thenReturn(List.of(
                    new SkuReservationRepository.HeldCartons("HCM", 25, 2)));

            assertThat(hold(2, 10).cartonsHeld()).as("both cartons of 10 are still to promise").isEqualTo(2);
        }

        @Test
        @DisplayName("a hold of before 4B-7, at no warehouse, is promised against the whole system: it leaves fewer "
                + "cartons of its count to every warehouse")
        void holdsAtNoWarehouseCountSystemWide() {
            levelAtHcmIs(cartonsOf(3, 10));
            when(skuReservations.heldCartonsOf("CUP-12OZ")).thenReturn(List.of(
                    new SkuReservationRepository.HeldCartons(null, 10, 2)));

            assertThat(hold(3, 10).cartonsHeld()).isEqualTo(1);
        }

        @Test
        @DisplayName("what other orders hold at the warehouse is not promised again; nothing left holds nothing")
        void otherHoldsAtTheWarehouse() {
            levelAtHcmIs(cartonsOf(10, 10));
            when(skuReservations.heldCartonsOf("CUP-12OZ")).thenReturn(List.of(
                    new SkuReservationRepository.HeldCartons("HCM", 10, 10)));

            HoldStockResult result = hold(1, 10);

            assertThat(result.cartonsHeld()).isZero();
            assertThat(result.reservationIds()).isEmpty();
            verify(skuReservations, never()).save(any());
        }

        @Test
        @DisplayName("units in doubt at a recount (a picker came up short) are not promised")
        void frozenUnitsAreNotPromised() {
            StockPackageRepository.Allocatable doubtful = levelAtHcmIs(cartonsOf(9, 10)).get(8);
            when(cycleCounts.frozen(anyCollection())).thenReturn(List.of(
                    new CycleCountRepository.Frozen(doubtful.id().value(), "CUP-12OZ", "HCM-A01-1-A", 10)));

            assertThat(hold(9, 10).cartonsHeld()).isEqualTo(8);
        }

        @Test
        @DisplayName("a carton on a location no release allocates from is on hand, never promised (review C4 P-1)")
        void cartonOffThePickableBinsIsNotPromised() {
            when(repository.findLevelLinesBySku(CUP)).thenReturn(List.of(
                    new StockLevelLine(new LocationId("HCM-QC01"), StockStatus.AVAILABLE, 50, 0, 1, 0)));
            when(repository.findAllocatable(anyCollection())).thenReturn(List.of(
                    new StockPackageRepository.Allocatable(new StockPackageId(UUID.randomUUID()), CUP.code(), "HCM-QC01", 50)));

            assertThat(hold(1, 50).cartonsHeld()).isZero();
            verify(skuReservations, never()).save(any());
        }

        @Test
        @DisplayName("a retried request gets the hold its first attempt made, and holds nothing again")
        void replay() {
            SkuReservation first = InventoryServiceImplReservationTest.hold("CUP-12OZ", 3, 10);
            when(skuReservations.findHeldForRequest(requestId)).thenReturn(List.of(first));

            HoldStockResult result = hold(3, 10);

            assertThat(result.reservationIds()).containsExactly(first.id().value());
            assertThat(result.cartonsHeld()).isEqualTo(3);
            verify(skuReservations, never()).save(any());
            verify(policies, never()).lock(anyString());
        }

        @Test
        @DisplayName("preparing an order's lines locks each SKU once, in sorted order, so two orders cannot deadlock")
        void prepareLocksSorted() {
            service.prepareReservation(Set.of(new Sku("ZED"), new Sku("ALPHA"), new Sku("MID")));

            InOrder order = inOrder(policies);
            order.verify(policies).lock("ALPHA");
            order.verify(policies).lock("MID");
            order.verify(policies).lock("ZED");
        }
    }

    // ------------------------------------------------------------------ allocate

    @Nested
    @DisplayName("allocating when the order is released to a warehouse (BR-STK-006)")
    class Allocate {

        @Test
        @DisplayName("an order with no live SKU reservation allocates nothing")
        void nothingToAllocate() {
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of());

            AllocateStockResult result = service.allocate(new AllocateStockCommand(ORDER, "hcm"));

            assertThat(result.warehouseCode()).isEqualTo("HCM");
            assertThat(result.allocations()).isEmpty();
        }

        @Test
        @DisplayName("all or nothing: a line whose reservation was released before the release is RESERVATION_NOT_ACTIVE")
        void lapsedReservationRefusesTheWholeOrder() {
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of());
            when(skuReservations.anyLapsedForOrder(ORDER)).thenReturn(true);

            assertThat(codeOf(() -> service.allocate(new AllocateStockCommand(ORDER, "HCM"))))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE);
        }

        @Test
        @DisplayName("FIFO: the carton received first is drawn first, across bins; the reservation becomes ALLOCATED")
        void allocatesFifo() {
            StockPackage later = received("HCM-A01-1-A", "L2", 25, NOW.minusSeconds(60));
            StockPackage sooner = received("HCM-A02-1-A", "L1", 25, NOW.minusSeconds(3600));
            availabilityIs(later, sooner);
            SkuReservation reservation = hold("CUP-12OZ", 2, 25);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(reservation));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(reservation));

            AllocateStockResult result = service.allocate(new AllocateStockCommand(ORDER, "HCM"));

            assertThat(result.allocations()).singleElement().satisfies(allocation -> {
                assertThat(allocation.reservationId()).isEqualTo(reservation.id().value());
                assertThat(allocation.holds()).extracting(h -> h.lotNumber() + ":" + h.quantity())
                        .containsExactly("L1:25", "L2:25");
            });
            assertThat(sooner.unitsHeld()).isEqualTo(25);
            assertThat(later.unitsHeld()).isEqualTo(25);
            assertThat(reservation.status()).isEqualTo(ReservationStatus.ALLOCATED);
            verify(skuReservations).save(reservation);
            verify(repository).save(sooner);
            verify(repository).save(later);
        }

        @Test
        @DisplayName("an allocation is one whole carton of the hold's count, pinned to its order and its reservation")
        void anAllocationIsOneCarton() {
            StockPackage bin = stock("HCM-A01-1-A", "L1", 10);
            availabilityIs(bin);
            SkuReservation reservation = hold("CUP-12OZ", 1, 10);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(reservation));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(reservation));

            service.allocate(new AllocateStockCommand(ORDER, "HCM"));

            Reservation allocation = bin.reservations().get(0);
            assertThat(allocation.isAllocation()).isTrue();
            assertThat(allocation.allocatedFrom()).isEqualTo(reservation.id().value());
            assertThat(allocation.unitsPerCarton()).isEqualTo(10);
            assertThat(bin.unitsHeld()).as("the carton is taken whole").isEqualTo(bin.unitsInStock());
            assertThat(bin.liveAllocation()).contains(allocation);
        }

        @Test
        @DisplayName("ADR-0011: a hold of cartons of 10 is allocated cartons of 10 only - an older carton of another "
                + "count is passed over, and two cartons of 5 are not added up into one of 10")
        void onlyCartonsOfTheHoldsCount() {
            StockPackage olderBig = received("HCM-A01-1-A", "L0", 20, NOW.minusSeconds(9000));
            StockPackage smallOne = received("HCM-A02-1-A", "L1", 5, NOW.minusSeconds(8000));
            StockPackage smallTwo = received("HCM-A03-1-A", "L1", 5, NOW.minusSeconds(7000));
            StockPackage right = received("HCM-A04-1-A", "L2", 10, NOW.minusSeconds(60));
            availabilityIs(olderBig, smallOne, smallTwo, right);
            SkuReservation reservation = hold("CUP-12OZ", 1, 10);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(reservation));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(reservation));

            AllocateStockResult result = service.allocate(new AllocateStockCommand(ORDER, "HCM"));

            assertThat(result.allocations()).singleElement().satisfies(allocation ->
                    assertThat(allocation.holds()).extracting(h -> h.lotNumber() + ":" + h.quantity())
                            .containsExactly("L2:10"));
            assertThat(right.unitsHeld()).isEqualTo(10);
            assertThat(olderBig.isHeld()).isFalse();
            assertThat(smallOne.isHeld()).isFalse();
            assertThat(smallTwo.isHeld()).isFalse();
        }

        @Test
        @DisplayName("ADR-0011: with no carton of the hold's count the release is INSUFFICIENT_STOCK, though cartons of "
                + "other counts hold more than enough units")
        void noCartonOfTheHoldsCountIsAShortage() {
            availabilityIs(stock("HCM-A01-1-A", "L1", 20), stock("HCM-A02-1-A", "L1", 5), stock("HCM-A03-1-A", "L1", 5));
            SkuReservation reservation = hold("CUP-12OZ", 1, 10);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(reservation));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(reservation));

            BusinessException refused = catchThrowableOfType(BusinessException.class,
                    () -> service.allocate(new AllocateStockCommand(ORDER, "HCM")));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
            assertThat(refused.getMessage()).contains("0 pickable").contains("carton(s) of 10").contains("10 are needed");
            assertThat(reservation.status()).isEqualTo(ReservationStatus.HELD);
        }

        @Test
        @DisplayName("4B-7, in cartons of one count: what other orders hold there of the hold's count is left to them; "
                + "what they hold of another count is not in the way")
        void roomBesideOtherOrdersHoldsOfTheSameCount() {
            availabilityIs(stock("HCM-A01-1-A", "L1", 10), stock("HCM-A02-1-A", "L1", 10));
            SkuReservation reservation = hold("CUP-12OZ", 1, 10);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(reservation));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(reservation));
            // This order's own carton, and two more cartons of 10 held for other orders: 3 held, 2 there.
            when(skuReservations.heldCartonsOf("CUP-12OZ")).thenReturn(List.of(
                    new SkuReservationRepository.HeldCartons("HCM", 10, 3)));

            BusinessException refused = catchThrowableOfType(BusinessException.class,
                    () -> service.allocate(new AllocateStockCommand(ORDER, "HCM")));
            assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
            assertThat(refused.getMessage()).contains("held for other orders");

            // The same cartons held in cartons of 25 by the others: none of the cartons of 10 is theirs.
            when(skuReservations.heldCartonsOf("CUP-12OZ")).thenReturn(List.of(
                    new SkuReservationRepository.HeldCartons("HCM", 10, 1),
                    new SkuReservationRepository.HeldCartons("HCM", 25, 2)));
            assertThat(service.allocate(new AllocateStockCommand(ORDER, "HCM")).allocations()).hasSize(1);
        }

        @Test
        @DisplayName("only the warehouse's own bins are drawn from, never a bin that is not pickable (module 06 BR-12)")
        void onlyPickableBinsOfTheWarehouse() {
            StockPackage otherWarehouse = stock("HN-A01-1-A", "L0", 40);
            StockPackage qc = stock("HCM-QC01", "L0", 40);
            StockPackage pickable = stock("HCM-A01-1-A", "L1", 40);
            availabilityIs(otherWarehouse, qc, pickable);
            SkuReservation reservation = hold("CUP-12OZ", 1, 40);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(reservation));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(reservation));

            service.allocate(new AllocateStockCommand(ORDER, "HCM"));

            assertThat(pickable.unitsHeld()).isEqualTo(40);
            assertThat(otherWarehouse.isHeld()).isFalse();
            assertThat(qc.isHeld()).isFalse();
        }

        @Test
        @DisplayName("a warehouse that cannot cover the line is INSUFFICIENT_STOCK, naming the warehouse and both numbers")
        void shortWarehouse() {
            availabilityIs(stock("HCM-A01-1-A", "L1", 10));
            SkuReservation reservation = hold("CUP-12OZ", 4, 10);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(reservation));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(reservation));

            BusinessException refused = catchThrowableOfType(BusinessException.class,
                    () -> service.allocate(new AllocateStockCommand(ORDER, "HCM")));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
            assertThat(refused.getMessage()).contains("HCM").contains("10 pickable").contains("40 are needed");
            assertThat(reservation.status()).isEqualTo(ReservationStatus.HELD);
        }

        @Test
        @DisplayName("units in doubt at a recount are not allocated")
        void frozenLayersAreSkipped() {
            StockPackage doubtful = stock("HCM-A01-1-A", "L1", 30);
            StockPackage fine = stock("HCM-A02-1-A", "L2", 30);
            availabilityIs(doubtful, fine);
            when(cycleCounts.frozen(anyCollection())).thenReturn(List.of(
                    new CycleCountRepository.Frozen(doubtful.id().value(), "CUP-12OZ", "HCM-A01-1-A", 30)));
            SkuReservation reservation = hold("CUP-12OZ", 1, 30);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(reservation));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(reservation));

            service.allocate(new AllocateStockCommand(ORDER, "HCM"));

            assertThat(doubtful.isHeld()).isFalse();
            assertThat(fine.unitsHeld()).isEqualTo(30);
        }

        @Test
        @DisplayName("two lines of one SKU do not draw the same carton twice: the second sees what the first took")
        void secondLineSeesWhatTheFirstTook() {
            StockPackage only30 = stock("HCM-A01-1-A", "L1", 30);
            StockPackage a20 = stock("HCM-A02-1-A", "L2", 20);
            availabilityIs(only30, a20);
            SkuReservation first = hold("CUP-12OZ", 1, 30);
            SkuReservation second = hold("CUP-12OZ", 1, 30);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(first, second));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(first, second));

            BusinessException refused = catchThrowableOfType(BusinessException.class,
                    () -> service.allocate(new AllocateStockCommand(ORDER, "HCM")));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
            assertThat(refused.getMessage()).as("the one carton of 30 went to the first line; the carton of 20 is not "
                    + "a carton of 30").contains("0 pickable").contains("30 are needed");
            assertThat(a20.isHeld()).isFalse();
        }

        @Test
        @DisplayName("each SKU is locked once, in sorted order, before the reservations and the cartons, which are "
                + "locked together in one call")
        void lockOrder() {
            StockPackage bin = stock("HCM-A01-1-A", "L1", 5);
            StockPackage other = stock("HCM-A02-1-A", "L2", 5);
            availabilityIs(bin, other);
            SkuReservation a = hold("CUP-12OZ", 1, 5);
            SkuReservation b = hold("CUP-12OZ", 1, 5);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(a, b));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(a, b));

            service.allocate(new AllocateStockCommand(ORDER, "HCM"));

            InOrder order = inOrder(policies, skuReservations, repository);
            order.verify(policies, times(1)).lock("CUP-12OZ");
            order.verify(skuReservations).findHeldForOrderForUpdate(ORDER);
            order.verify(repository, times(1)).findAllByIdForUpdate(Set.of(bin.id(), other.id()));
        }
    }

    // ------------------------------------------------------------------ cover and shrink (WF4-COUNT-EDGE)

    @Nested
    @DisplayName("what covers the holds at a warehouse, and cutting a hold (WF4-COUNT-EDGE a; ADR-0011)")
    class CoverAndShrink {

        private SkuReservation held(int cartons, int unitsPerCarton) {
            return InventoryServiceImplReservationTest.hold("CUP-12OZ", cartons, unitsPerCarton);
        }

        @Test
        @DisplayName("covered: the cover answers in units - every free carton there, every hold's units")
        void coveredAnswersInUnits() {
            levelAtHcmIs(10, 10, 10);
            SkuReservation hold = held(2, 10);
            when(skuReservations.findHeldAt("CUP-12OZ", "HCM")).thenReturn(List.of(hold));

            com.stockflow.inventory.api.SkuHoldCover cover = service.skuHoldCover(CUP, "hcm");

            assertThat(cover.coverableUnits()).isEqualTo(30);
            assertThat(cover.heldUnits()).isEqualTo(20);
            assertThat(cover.uncoveredUnits()).isZero();
            assertThat(cover.holds()).singleElement().satisfies(h -> {
                assertThat(h.reservationId()).isEqualTo(hold.id().value());
                assertThat(h.quantity()).as("other modules keep reading units").isEqualTo(20);
            });
        }

        @Test
        @DisplayName("a hold is covered by cartons of its count only: free cartons of another count make no shortage up")
        void coverIsPerCount() {
            // One carton of 10 left for holds of 3 cartons of 10; 4 cartons of 25 free beside it (100 units).
            levelAtHcmIs(10, 25, 25, 25, 25);
            when(skuReservations.findHeldAt("CUP-12OZ", "HCM")).thenReturn(List.of(held(3, 10), held(1, 25)));

            com.stockflow.inventory.api.SkuHoldCover cover = service.skuHoldCover(CUP, "HCM");

            assertThat(cover.heldUnits()).isEqualTo(55);
            assertThat(cover.uncoveredUnits()).as("two cartons of 10 short, whatever the cartons of 25 hold")
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("shrinking speaks units and keeps whole cartons: a figure that is not a number of the hold's "
                + "cartons is VALIDATION_FAILED, and nothing is released")
        void shrinkRefusesPartOfACarton() {
            SkuReservation hold = held(3, 10);
            UUID id = hold.id().value();
            when(skuReservations.findById(ReservationId.of(id))).thenReturn(Optional.of(hold));

            BusinessException refused = catchThrowableOfType(BusinessException.class, () -> service.shrinkHold(id, 15));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            assertThat(refused.getMessage()).contains("3 carton(s) of 10").contains("15");
            assertThat(hold.status()).isEqualTo(ReservationStatus.HELD);
            verify(skuReservations, never()).save(any());
        }

        @Test
        @DisplayName("shrinking to a whole number of cartons releases the hold and holds that many cartons of the same "
                + "count; the request id is still named after the units kept")
        void shrinkKeepsWholeCartons() {
            SkuReservation hold = held(3, 10);
            hold.pullDomainEvents();
            UUID id = hold.id().value();
            when(skuReservations.findById(ReservationId.of(id))).thenReturn(Optional.of(hold));
            when(skuReservations.findByIdForUpdate(ReservationId.of(id))).thenReturn(Optional.of(hold));
            when(skuReservations.save(any())).thenAnswer(call -> call.getArgument(0));

            Optional<UUID> kept = service.shrinkHold(id, 20);

            org.mockito.ArgumentCaptor<SkuReservation> saved = org.mockito.ArgumentCaptor.forClass(SkuReservation.class);
            verify(skuReservations, times(2)).save(saved.capture());
            SkuReservation smaller = saved.getAllValues().get(1);
            assertThat(hold.status()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(kept).contains(smaller.id().value());
            assertThat(smaller.cartons()).isEqualTo(2);
            assertThat(smaller.unitsPerCarton()).isEqualTo(10);
            assertThat(smaller.requestId()).as("a retry from before the change converges on the same hold")
                    .isEqualTo(UUID.nameUUIDFromBytes(("shrink:%s:%d".formatted(id, 20))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    // ------------------------------------------------------------------ hold views

    @Nested
    @DisplayName("an order's holds")
    class Holds {

        @Test
        @DisplayName("holds of an order list SKU reservations without a bin, then stock holds with theirs")
        void holdsOf() {
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(hold("CUP-12OZ", 2, 5)));
            when(repository.findHeldForOrder(ORDER)).thenReturn(List.of(new HeldStock("LID-12OZ", "HCM-A01-1-A", 4)));

            List<OrderHold> holds = service.holdsOf(ORDER);

            assertThat(holds).containsExactly(new OrderHold("CUP-12OZ", null, 10),
                    new OrderHold("LID-12OZ", "HCM-A01-1-A", 4));
        }
    }

    // ------------------------------------------------------------------ release

    @Nested
    @DisplayName("releasing a hold")
    class Release {

        private final UUID holdId = UUID.randomUUID();

        private CurrentUser clerk() {
            return new CurrentUser(UUID.randomUUID(), "clerk", Set.of(Role.WAREHOUSE_STAFF), Set.of(),
                    DataScope.WAREHOUSE, Set.of("HCM"), Set.of(UUID.randomUUID()));
        }

        @Test
        @DisplayName("a SKU reservation is released with the reason given and the release is announced")
        void releasesSkuReservation() {
            SkuReservation reservation = hold("CUP-12OZ", 1, 10);
            when(skuReservations.exists(ReservationId.of(holdId))).thenReturn(true);
            when(skuReservations.findByIdForUpdate(ReservationId.of(holdId))).thenReturn(Optional.of(reservation));

            service.release(holdId, "order_cancelled");

            assertThat(reservation.releaseReason()).isEqualTo(ReleaseReason.ORDER_CANCELLED);
            verify(skuReservations).save(reservation);
            verify(publisher).publishEvent(any(StockReservationReleased.class));
        }

        @Test
        @DisplayName("releasing an already closed SKU reservation is a no-op: cancel and a release by hand can race")
        void alreadyClosedIsANoOp() {
            SkuReservation reservation = hold("CUP-12OZ", 1, 10);
            reservation.release(ReleaseReason.MANUAL_OVERRIDE, NOW);
            reservation.pullDomainEvents();
            when(skuReservations.exists(ReservationId.of(holdId))).thenReturn(true);
            when(skuReservations.findByIdForUpdate(ReservationId.of(holdId))).thenReturn(Optional.of(reservation));

            service.release(holdId, "ORDER_CANCELLED");

            verify(skuReservations, never()).save(any());
            assertThat(reservation.releaseReason()).isEqualTo(ReleaseReason.MANUAL_OVERRIDE);
        }

        @Test
        @DisplayName("a missing or unknown reason is recorded as MANUAL_OVERRIDE, not refused")
        void unknownReason() {
            SkuReservation reservation = hold("CUP-12OZ", 1, 10);
            when(skuReservations.exists(ReservationId.of(holdId))).thenReturn(true);
            when(skuReservations.findByIdForUpdate(ReservationId.of(holdId))).thenReturn(Optional.of(reservation));

            service.release(holdId, "because I said so");
            assertThat(reservation.releaseReason()).isEqualTo(ReleaseReason.MANUAL_OVERRIDE);

            SkuReservation other = hold("CUP-12OZ", 1, 10);
            when(skuReservations.findByIdForUpdate(ReservationId.of(holdId))).thenReturn(Optional.of(other));
            service.release(holdId, null);
            assertThat(other.releaseReason()).isEqualTo(ReleaseReason.MANUAL_OVERRIDE);
        }

        @Test
        @DisplayName("BR-SEC-002: a SKU reservation is on no bin, so a warehouse-bound caller cannot release it")
        void skuReservationOutOfReachOfAClerk() {
            when(skuReservations.exists(ReservationId.of(holdId))).thenReturn(true);

            WithCurrentUser.run(clerk(), () ->
                    assertThat(codeOf(() -> service.release(holdId, "MANUAL_OVERRIDE")))
                            .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND));
        }

        @Test
        @DisplayName("an allocation is released on its carton, which is free again")
        void releasesStockHold() {
            StockPackage bin = stock("HCM-A01-1-A", "L1", 50);
            Reservation reservation = bin.allocate(hold("CUP-12OZ", 1, 50), UUID.randomUUID(), NOW);

            service.release(reservation.id().value(), "PAYMENT_FAILED");

            assertThat(bin.isFree()).isTrue();
            assertThat(bin.unitsAvailable()).isEqualTo(50);
            verify(repository).save(bin);
        }

        @Test
        @DisplayName("an unknown reservation is RESERVATION_NOT_FOUND")
        void unknown() {
            assertThat(codeOf(() -> service.release(holdId, "MANUAL_OVERRIDE")))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
        }

        @Test
        @DisplayName("a clerk cannot release a hold in another warehouse's bin")
        void otherWarehouseBin() {
            StockPackage bin = stock("HN-A01-1-A", "L1", 50);
            Reservation reservation = bin.allocate(hold("CUP-12OZ", 1, 50), UUID.randomUUID(), NOW);

            WithCurrentUser.run(clerk(), () ->
                    assertThat(codeOf(() -> service.release(reservation.id().value(), "MANUAL_OVERRIDE")))
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
            verify(repository, never()).save(any());
        }
    }
}
