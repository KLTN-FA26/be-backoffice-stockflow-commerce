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
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.SkuReservation;
import com.stockflow.inventory.internal.domain.SkuReservationRepository;
import com.stockflow.inventory.internal.domain.StockAllocator;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockLevelLine;
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
 * re-arm and sweeper are gone (V20261117000300).
 */
class InventoryServiceImplReservationTest {

    private static final Sku CUP = new Sku("CUP-12OZ");
    private static final UUID ORDER = UUID.randomUUID();

    private final StockItemRepository repository = mock(StockItemRepository.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final StockOperations operations = mock(StockOperations.class);
    private final InventoryItemDirectory itemDirectory = mock(InventoryItemDirectory.class);
    private final InventoryPolicyRepository policies = mock(InventoryPolicyRepository.class);
    private final SkuReservationRepository skuReservations = mock(SkuReservationRepository.class);
    private final LocationDirectory locations = mock(LocationDirectory.class);
    private final CycleCountRepository cycleCounts = mock(CycleCountRepository.class);
    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final InventoryServiceImpl service = new InventoryServiceImpl(repository,
            new InventoryEventPublisher(publisher), operations, itemDirectory, Clock.fixed(NOW, ZoneOffset.UTC),
            policies, skuReservations, locations, cycleCounts, stockPackages);

    private final Map<StockItemId, StockItem> rows = new HashMap<>();

    @BeforeEach
    void defaults() {
        when(repository.findByIdForUpdate(any())).thenAnswer(c -> Optional.ofNullable(rows.get(c.<StockItemId>getArgument(0))));
        when(repository.findById(any())).thenAnswer(c -> Optional.ofNullable(rows.get(c.<StockItemId>getArgument(0))));
        when(locations.pickable(anyCollection())).thenAnswer(c -> {
            Map<String, UUID> pickable = new HashMap<>();
            c.<java.util.Collection<String>>getArgument(0).stream().filter(code -> !code.contains("-QC"))
                    .forEach(code -> pickable.put(code, UUID.randomUUID()));
            return pickable;
        });
    }

    /** A sellable carton's row: only a carton's row takes an allocation (kltn-docs 20 BR-14). */
    private StockItem stock(String location, String lot, int onHand) {
        return received(location, lot, onHand, null);
    }

    /** As {@link #stock}, received at {@code receivedAt}: the time FIFO ranks by. */
    private StockItem received(String location, String lot, int onHand, Instant receivedAt) {
        StockItem item = new StockItem(StockItemId.newId(), CUP, new LocationId(location), lot, Quantity.of(onHand),
                StockStatus.AVAILABLE, List.of(), 0L, receivedAt, UUID.randomUUID(),
                new StockPackageId(UUID.randomUUID()));
        rows.put(item.id(), item);
        return item;
    }

    /** Each row as the whole content of a sealed carton: what allocation draws on since team plan 4C-2. */
    private void availabilityIs(StockItem... items) {
        when(repository.findAvailabilityBySku(CUP)).thenReturn(java.util.Arrays.stream(items).map(item ->
                new StockAllocator.Candidate(item.id(), item.location(), item.lotNumber(), item.status(), item.available(), item.receivedAt(), UUID.randomUUID(), true)).toList());
    }

    /**
     * The cup's sellable, unheld units at HCM, one whole free carton per argument on a pickable bin: the level line
     * Inventory Level reads and the allocatable rows a warehouse promises from (review C4 P-1, {@link PickableStock}).
     */
    private List<StockItemRepository.Allocatable> levelAtHcmIs(int... cartons) {
        when(repository.findLevelLinesBySku(CUP)).thenReturn(List.of(new StockLevelLine(
                new LocationId("HCM-A01-1-A"), StockStatus.AVAILABLE, java.util.Arrays.stream(cartons).sum(), 0)));
        List<StockItemRepository.Allocatable> allocatable = java.util.Arrays.stream(cartons)
                .mapToObj(units -> new StockItemRepository.Allocatable(StockItemId.newId(), CUP.code(), "HCM-A01-1-A",
                        units))
                .toList();
        when(repository.findAllocatable(anyCollection())).thenReturn(allocatable);
        return allocatable;
    }

    private static SkuReservation hold(String sku, int units) {
        return SkuReservation.holdAt("HCM", new Sku(sku), ORDER, UUID.randomUUID(), Quantity.of(units), 1000, NOW);
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
            levelAtHcmIs(100);

            HoldStockResult result = hold(3, 10);

            org.mockito.ArgumentCaptor<SkuReservation> saved = org.mockito.ArgumentCaptor.forClass(SkuReservation.class);
            verify(skuReservations).save(saved.capture());
            assertThat(saved.getValue().quantity().value()).isEqualTo(30);
            assertThat(saved.getValue().warehouseCode()).isEqualTo("HCM");
            assertThat(saved.getValue().expiresAt()).isNull();
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
            levelAtHcmIs(25);

            HoldStockResult result = hold(3, 10);

            assertThat(result.cartonsHeld()).isEqualTo(2);
            org.mockito.ArgumentCaptor<SkuReservation> saved = org.mockito.ArgumentCaptor.forClass(SkuReservation.class);
            verify(skuReservations).save(saved.capture());
            assertThat(saved.getValue().quantity().value()).isEqualTo(20);
        }

        @Test
        @DisplayName("what other orders hold at the warehouse is not promised again; nothing left holds nothing")
        void otherHoldsAtTheWarehouse() {
            levelAtHcmIs(100);
            when(skuReservations.heldBySkuAt("CUP-12OZ")).thenReturn(Map.of("HCM", 95L));

            HoldStockResult result = hold(1, 10);

            assertThat(result.cartonsHeld()).isZero();
            assertThat(result.reservationIds()).isEmpty();
            verify(skuReservations, never()).save(any());
        }

        @Test
        @DisplayName("units in doubt at a recount (a picker came up short) are not promised")
        void frozenUnitsAreNotPromised() {
            StockItemRepository.Allocatable doubtful = levelAtHcmIs(85, 15).get(1);
            when(cycleCounts.frozen(anyCollection())).thenReturn(List.of(
                    new CycleCountRepository.Frozen(doubtful.id().value(), "CUP-12OZ", "HCM-A01-1-A", 15)));

            assertThat(hold(9, 10).cartonsHeld()).isEqualTo(8);
        }

        @Test
        @DisplayName("a carton on a location no release allocates from is on hand, never promised (review C4 P-1)")
        void cartonOffThePickableBinsIsNotPromised() {
            when(repository.findLevelLinesBySku(CUP)).thenReturn(List.of(
                    new StockLevelLine(new LocationId("HCM-QC01"), StockStatus.AVAILABLE, 50, 0)));
            when(repository.findAllocatable(anyCollection())).thenReturn(List.of(
                    new StockItemRepository.Allocatable(StockItemId.newId(), CUP.code(), "HCM-QC01", 50)));

            assertThat(hold(1, 10).cartonsHeld()).isZero();
            verify(skuReservations, never()).save(any());
        }

        @Test
        @DisplayName("a retried request gets the hold its first attempt made, and holds nothing again")
        void replay() {
            SkuReservation first = InventoryServiceImplReservationTest.hold("CUP-12OZ", 30);
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
        @DisplayName("FIFO: the lot received first is drawn first, across bins; the reservation becomes ALLOCATED")
        void allocatesFifo() {
            StockItem later = received("HCM-A01-1-A", "L2", 30, NOW.minusSeconds(60));
            StockItem sooner = received("HCM-A02-1-A", "L1", 20, NOW.minusSeconds(3600));
            availabilityIs(later, sooner);
            SkuReservation reservation = hold("CUP-12OZ", 50);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(reservation));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(reservation));

            AllocateStockResult result = service.allocate(new AllocateStockCommand(ORDER, "HCM"));

            assertThat(result.allocations()).singleElement().satisfies(allocation -> {
                assertThat(allocation.reservationId()).isEqualTo(reservation.id().value());
                assertThat(allocation.holds()).extracting(h -> h.lotNumber() + ":" + h.quantity())
                        .containsExactly("L1:20", "L2:30");
            });
            assertThat(sooner.reserved().value()).isEqualTo(20);
            assertThat(later.reserved().value()).isEqualTo(30);
            assertThat(reservation.status()).isEqualTo(ReservationStatus.ALLOCATED);
            verify(skuReservations).save(reservation);
            verify(repository).save(sooner);
            verify(repository).save(later);
        }

        @Test
        @DisplayName("allocations are never expiring: they are pinned to their order")
        void allocationsDoNotExpire() {
            StockItem bin = stock("HCM-A01-1-A", "L1", 10);
            availabilityIs(bin);
            SkuReservation reservation = hold("CUP-12OZ", 10);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(reservation));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(reservation));

            service.allocate(new AllocateStockCommand(ORDER, "HCM"));

            Reservation allocation = bin.reservations().get(0);
            assertThat(allocation.isAllocation()).isTrue();
            assertThat(allocation.allocatedFrom()).isEqualTo(reservation.id().value());
            assertThat(allocation.expiresAt()).isNull();
        }

        @Test
        @DisplayName("only the warehouse's own bins are drawn from, never a bin that is not pickable (module 06 BR-12)")
        void onlyPickableBinsOfTheWarehouse() {
            StockItem otherWarehouse = stock("HN-A01-1-A", "L0", 500);
            StockItem qc = stock("HCM-QC01", "L0", 500);
            StockItem pickable = stock("HCM-A01-1-A", "L1", 40);
            availabilityIs(otherWarehouse, qc, pickable);
            SkuReservation reservation = hold("CUP-12OZ", 40);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(reservation));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(reservation));

            service.allocate(new AllocateStockCommand(ORDER, "HCM"));

            assertThat(pickable.reserved().value()).isEqualTo(40);
            assertThat(otherWarehouse.reserved().value()).isZero();
            assertThat(qc.reserved().value()).isZero();
        }

        @Test
        @DisplayName("a warehouse that cannot cover the line is INSUFFICIENT_STOCK, naming the warehouse and both numbers")
        void shortWarehouse() {
            availabilityIs(stock("HCM-A01-1-A", "L1", 10));
            SkuReservation reservation = hold("CUP-12OZ", 40);
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
            StockItem doubtful = stock("HCM-A01-1-A", "L1", 30);
            StockItem fine = stock("HCM-A02-1-A", "L2", 30);
            availabilityIs(doubtful, fine);
            when(cycleCounts.frozen(anyCollection())).thenReturn(List.of(
                    new CycleCountRepository.Frozen(doubtful.id().value(), "CUP-12OZ", "HCM-A01-1-A", 30)));
            SkuReservation reservation = hold("CUP-12OZ", 30);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(reservation));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(reservation));

            service.allocate(new AllocateStockCommand(ORDER, "HCM"));

            assertThat(doubtful.reserved().value()).isZero();
            assertThat(fine.reserved().value()).isEqualTo(30);
        }

        @Test
        @DisplayName("two lines of one SKU do not draw the same units twice: the second sees what the first took")
        void secondLineSeesWhatTheFirstTook() {
            StockItem first30 = stock("HCM-A01-1-A", "L1", 30);
            StockItem then20 = stock("HCM-A02-1-A", "L2", 20);
            availabilityIs(first30, then20);
            SkuReservation first = hold("CUP-12OZ", 30);
            SkuReservation second = hold("CUP-12OZ", 30);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(first, second));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(first, second));

            BusinessException refused = catchThrowableOfType(BusinessException.class,
                    () -> service.allocate(new AllocateStockCommand(ORDER, "HCM")));

            assertThat(refused.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_STOCK);
            assertThat(refused.getMessage()).contains("20 pickable");
        }

        @Test
        @DisplayName("each SKU is locked once, in sorted order, before the reservations and the rows")
        void lockOrder() {
            StockItem bin = stock("HCM-A01-1-A", "L1", 5);
            StockItem other = stock("HCM-A02-1-A", "L2", 5);
            availabilityIs(bin, other);
            SkuReservation a = hold("CUP-12OZ", 5);
            SkuReservation b = hold("CUP-12OZ", 5);
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(a, b));
            when(skuReservations.findHeldForOrderForUpdate(ORDER)).thenReturn(List.of(a, b));

            service.allocate(new AllocateStockCommand(ORDER, "HCM"));

            InOrder order = inOrder(policies, skuReservations, repository);
            order.verify(policies, times(1)).lock("CUP-12OZ");
            order.verify(skuReservations).findHeldForOrderForUpdate(ORDER);
            order.verify(repository).findByIdForUpdate(bin.id());
        }
    }

    // ------------------------------------------------------------------ hold views

    @Nested
    @DisplayName("an order's holds")
    class Holds {

        @Test
        @DisplayName("holds of an order list SKU reservations without a bin, then stock holds with theirs")
        void holdsOf() {
            when(skuReservations.findHeldForOrder(ORDER)).thenReturn(List.of(hold("CUP-12OZ", 10)));
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
            SkuReservation reservation = hold("CUP-12OZ", 10);
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
            SkuReservation reservation = hold("CUP-12OZ", 10);
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
            SkuReservation reservation = hold("CUP-12OZ", 10);
            when(skuReservations.exists(ReservationId.of(holdId))).thenReturn(true);
            when(skuReservations.findByIdForUpdate(ReservationId.of(holdId))).thenReturn(Optional.of(reservation));

            service.release(holdId, "because I said so");
            assertThat(reservation.releaseReason()).isEqualTo(ReleaseReason.MANUAL_OVERRIDE);

            SkuReservation other = hold("CUP-12OZ", 10);
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
        @DisplayName("a hold on a stock row is released and the units are free again")
        void releasesStockHold() {
            StockItem bin = stock("HCM-A01-1-A", "L1", 50);
            Reservation reservation = bin.allocate(hold("CUP-12OZ", 20), UUID.randomUUID(), Quantity.of(20), NOW);
            when(repository.findByReservationIdForUpdate(reservation.id())).thenReturn(Optional.of(bin));

            service.release(reservation.id().value(), "PAYMENT_FAILED");

            assertThat(bin.available().value()).isEqualTo(50);
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
            StockItem bin = stock("HN-A01-1-A", "L1", 50);
            Reservation reservation = bin.allocate(hold("CUP-12OZ", 20), UUID.randomUUID(), Quantity.of(20), NOW);
            when(repository.findByReservationIdForUpdate(reservation.id())).thenReturn(Optional.of(bin));

            WithCurrentUser.run(clerk(), () ->
                    assertThat(codeOf(() -> service.release(reservation.id().value(), "MANUAL_OVERRIDE")))
                            .isEqualTo(ErrorCode.OUT_OF_DATA_SCOPE));
            verify(repository, never()).save(any());
        }
    }
}
