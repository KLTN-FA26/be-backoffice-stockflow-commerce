package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.StockDeducted;
import com.stockflow.inventory.consumption.ConsumedPick;
import com.stockflow.inventory.consumption.PutBackCommand;
import com.stockflow.inventory.consumption.StageCommand;
import com.stockflow.inventory.consumption.StagedLine;
import com.stockflow.inventory.consumption.StagedPick;
import com.stockflow.inventory.internal.domain.LocationDirectory;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockPackage;
import com.stockflow.inventory.internal.domain.StockPackageId;
import com.stockflow.inventory.internal.domain.StockPackageRepository;
import com.stockflow.inventory.internal.domain.StockPackageStatus;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SCRUM-275: the in-process deduction of a pick line and the put-back of a cancelled order, without a
 * database — the ledger is an in-memory list, the carton repository a mock over a map handing out real aggregates.
 * The carton is the stock (ADR-0011): a pick takes it out of stock through its allocation, a put-back brings that very
 * carton back, and staging moves it with its allocation still live.
 */
class PickConsumptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final Sku CUP = new Sku("LY-GIAY-DEMO-12OZ-XANH");
    private static final LocationId BIN = new LocationId("HCM-A01-2-B");
    private static final LocationId OTHER_BIN = new LocationId("HCM-A02-1-A");
    private static final UUID PICKER = UUID.randomUUID();
    private static final UUID LOT = UUID.randomUUID();
    private static final Instant RECEIVED = NOW.minusSeconds(86_400);

    private final List<StockMovement> ledgerRows = new ArrayList<>();
    private final StockMovementLog ledger = new StockMovementLog() {
        @Override
        public void append(StockMovement movement) {
            ledgerRows.add(movement);
            writtenAtOnceBeforeLine.add(List.copyOf(writtenAtOnce));
        }

        @Override
        public List<StockMovementLog.MovedCarton> cartonsMovedBy(
                StockMovement.ReferenceType referenceType, UUID referenceId) {
            return List.of();
        }

        @Override
        public Optional<StockMovement> findByReference(StockMovement.MovementType type,
                                                       StockMovement.ReferenceType referenceType, UUID referenceId) {
            return ledgerRows.stream().filter(row -> row.type() == type && row.referenceType() == referenceType
                    && row.referenceId().equals(referenceId)).findFirst();
        }

        @Override
        public List<StockMovement> findAllByReference(StockMovement.MovementType type,
                                                      StockMovement.ReferenceType referenceType, UUID referenceId) {
            return ledgerRows.stream().filter(row -> row.type() == type && row.referenceType() == referenceType
                    && row.referenceId().equals(referenceId)).toList();
        }

        @Override
        public int countByReference(java.util.Collection<StockMovement.MovementType> types,
                                    StockMovement.ReferenceType referenceType, UUID referenceId) {
            return (int) ledgerRows.stream().filter(row -> types.contains(row.type())
                    && row.referenceType() == referenceType && row.referenceId().equals(referenceId)).count();
        }
    };
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final LocationDirectory locations = mock(LocationDirectory.class);
    private final InventoryPolicyRepository policies = mock(InventoryPolicyRepository.class);
    private final com.stockflow.inventory.internal.domain.CycleCountRepository cycleCounts =
            mock(com.stockflow.inventory.internal.domain.CycleCountRepository.class);
    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final CartonPlacement placement = mock(CartonPlacement.class);
    private final PickConsumptionService service = new PickConsumptionService(ledger,
            new InventoryEventPublisher(publisher), locations, policies,
            new StockPackageContents(stockPackages, locations,
                    mock(com.stockflow.inventory.internal.domain.ReturnedCartonOwnership.class)), Clock.fixed(NOW, ZoneOffset.UTC),
            cycleCounts, stockPackages, placement);
    /** The cartons the repository knows, by id. */
    private final Map<StockPackageId, StockPackage> cartons = new HashMap<>();
    private final List<StockPackage> saved = new ArrayList<>();
    /** Cartons written to the database at once ({@code saveNow}), and what had been at each ledger line. */
    private final List<StockPackage> writtenAtOnce = new ArrayList<>();
    private final List<List<StockPackage>> writtenAtOnceBeforeLine = new ArrayList<>();

    @BeforeEach
    void store() {
        when(stockPackages.save(any())).thenAnswer(call -> {
            StockPackage carton = call.getArgument(0);
            saved.add(carton);
            return carton;
        });
        when(stockPackages.saveNow(any())).thenAnswer(call -> {
            StockPackage carton = call.getArgument(0);
            writtenAtOnce.add(carton);
            return carton;
        });
        when(stockPackages.findById(any())).thenAnswer(call ->
                Optional.ofNullable(cartons.get(call.<StockPackageId>getArgument(0))));
        when(stockPackages.findByIdForUpdate(any())).thenAnswer(call ->
                Optional.ofNullable(cartons.get(call.<StockPackageId>getArgument(0))));
        when(stockPackages.findAllById(any())).thenAnswer(call ->
                call.<java.util.Collection<StockPackageId>>getArgument(0).stream().map(cartons::get)
                        .filter(java.util.Objects::nonNull).toList());
        // In id order, each once: as the adapter locks them.
        when(stockPackages.findAllByIdForUpdate(any())).thenAnswer(call ->
                call.<java.util.Collection<StockPackageId>>getArgument(0).stream().distinct()
                        .sorted(java.util.Comparator.comparing(StockPackageId::value)).map(cartons::get)
                        .filter(java.util.Objects::nonNull).toList());
        when(stockPackages.findIdByReservationId(any())).thenAnswer(call -> holderOf(call.getArgument(0))
                .map(StockPackage::id));
        when(stockPackages.findByReservationId(any())).thenAnswer(call -> holderOf(call.getArgument(0)));
        when(locations.stateOf(any())).thenReturn(LocationDirectory.LocationState.USABLE);
    }

    private Optional<StockPackage> holderOf(ReservationId reservation) {
        return cartons.values().stream().filter(carton -> carton.findReservation(reservation).isPresent()).findFirst();
    }

    private static ErrorCode refusal(ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    private static Reservation hold(UUID reservationId, int units, ReservationStatus status) {
        return new Reservation(ReservationId.of(reservationId), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), units, NOW.minusSeconds(3600), status,
                status == ReservationStatus.RELEASED ? ReleaseReason.ORDER_CANCELLED : null,
                status == ReservationStatus.HELD ? null : NOW.minusSeconds(60), UUID.randomUUID());
    }

    /**
     * A carton of {@code units} of {@code sku} at {@code location}, with one allocation in {@code holdStatus}. In
     * stock in {@code stockStatus}; out of stock (CONSUMED, as a pick leaves it) when that is null.
     */
    private StockPackage carton(String lpn, Sku sku, LocationId location, int units, StockStatus stockStatus,
                                ReservationStatus holdStatus, UUID reservationId) {
        boolean inStock = stockStatus != null;
        StockPackage carton = new StockPackage(new StockPackageId(UUID.randomUUID()), lpn, sku, UUID.randomUUID(),
                UUID.randomUUID(), units, NOW.minusSeconds(7200), location,
                inStock ? StockPackageStatus.IN_STOCK : StockPackageStatus.CONSUMED, stockStatus, LOT, "LOT-1",
                RECEIVED, null, inStock ? null : NOW.minusSeconds(60),
                List.of(hold(reservationId, units, holdStatus)), 0L);
        cartons.put(carton.id(), carton);
        return carton;
    }

    /** The AVAILABLE carton of {@code units} sealed at {@code location}, with one allocation of it. */
    private StockPackage cartonWithHold(LocationId location, int units, ReservationStatus status, UUID reservationId) {
        return carton("LPN-20261113-000009", CUP, location, units, StockStatus.AVAILABLE, status, reservationId);
    }

    @Nested
    @DisplayName("deducting a pick line")
    class Deducting {

        @Test
        @DisplayName("the carton leaves stock whole, the hold is consumed, one PICK ledger row names the line and the carton, StockDeducted goes out")
        void deductsOnce() {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            StockPackage carton = cartonWithHold(BIN, 3, ReservationStatus.HELD, hold);

            ConsumedPick consumed = service.consumeForPick(hold, line, PICKER);

            assertThat(consumed).isEqualTo(new ConsumedPick(hold, "LY-GIAY-DEMO-12OZ-XANH", "HCM-A01-2-B", "LOT-1", 3,
                    false));
            assertThat(carton.status()).as("picked: out of stock, in the same step").isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(carton.stockStatus()).isNull();
            assertThat(carton.unitsInStock()).isZero();
            assertThat(carton.findReservation(ReservationId.of(hold)).orElseThrow().status())
                    .isEqualTo(ReservationStatus.CONSUMED);
            assertThat(saved).containsExactly(carton);
            assertThat(ledgerRows).singleElement().satisfies(row -> {
                assertThat(row.type()).isEqualTo(StockMovement.MovementType.PICK);
                assertThat(row.referenceType()).isEqualTo(StockMovement.ReferenceType.PICK_LINE);
                assertThat(row.referenceId()).isEqualTo(line);
                assertThat(row.from()).isEqualTo(BIN);
                assertThat(row.to()).isNull();
                assertThat(row.stockPackage()).as("the line is the carton, whole").isEqualTo(carton.id());
                assertThat(row.lotNumber()).isEqualTo("LOT-1");
                assertThat(row.status()).as("the status it had when it left").isEqualTo(StockStatus.AVAILABLE);
                assertThat(row.actorId()).isEqualTo(PICKER);
            });
            verify(publisher).publishEvent(new StockDeducted(
                    carton.findReservation(ReservationId.of(hold)).orElseThrow().orderId(), "LY-GIAY-DEMO-12OZ-XANH",
                    "HCM-A01-2-B", 3));
        }

        @Test
        @DisplayName("the SKU is locked, then the carton - and nothing after it")
        void lockOrder() {
            UUID hold = UUID.randomUUID();
            StockPackage carton = cartonWithHold(BIN, 3, ReservationStatus.HELD, hold);

            service.consumeForPick(hold, UUID.randomUUID(), PICKER);

            var order = org.mockito.Mockito.inOrder(policies, stockPackages);
            order.verify(stockPackages).findByReservationId(ReservationId.of(hold));
            order.verify(policies).lock("LY-GIAY-DEMO-12OZ-XANH");
            order.verify(stockPackages).findByIdForUpdate(carton.id());
            order.verify(stockPackages).save(carton);
        }

        @Test
        @DisplayName("the same line again changes nothing and says it was already consumed")
        void alreadyConsumedByThisLine() {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            StockPackage carton = cartonWithHold(BIN, 3, ReservationStatus.HELD, hold);
            service.consumeForPick(hold, line, PICKER);
            saved.clear();

            ConsumedPick again = service.consumeForPick(hold, line, PICKER);

            assertThat(again.alreadyConsumed()).isTrue();
            assertThat(again.quantity()).isEqualTo(3);
            assertThat(again.locationCode()).isEqualTo("HCM-A01-2-B");
            assertThat(carton.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(ledgerRows).hasSize(1);
            assertThat(saved).isEmpty();
        }

        @Test
        @DisplayName("a hold consumed by another line is not active: refused, nothing written")
        void consumedByAnotherLine() {
            UUID hold = UUID.randomUUID();
            StockPackage carton = cartonWithHold(BIN, 3, ReservationStatus.HELD, hold);
            service.consumeForPick(hold, UUID.randomUUID(), PICKER);
            saved.clear();

            assertThat(refusal(() -> service.consumeForPick(hold, UUID.randomUUID(), PICKER)))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE);
            assertThat(carton.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(ledgerRows).hasSize(1);
            assertThat(saved).isEmpty();
        }

        @Test
        @DisplayName("a released or expired hold: RESERVATION_NOT_ACTIVE, the carton stays in stock, nothing written, no event")
        void releasedOrExpiredHold() {
            for (ReservationStatus status : List.of(ReservationStatus.RELEASED, ReservationStatus.EXPIRED)) {
                UUID hold = UUID.randomUUID();
                StockPackage carton = cartonWithHold(BIN, 10, status, hold);

                assertThat(refusal(() -> service.consumeForPick(hold, UUID.randomUUID(), PICKER)))
                        .as(status.name()).isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE);
                assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
                assertThat(carton.unitsInStock()).isEqualTo(10);
            }
            assertThat(ledgerRows).isEmpty();
            assertThat(saved).isEmpty();
            verify(publisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("an unknown hold: RESERVATION_NOT_FOUND")
        void unknownHold() {
            UUID hold = UUID.randomUUID();

            assertThat(refusal(() -> service.consumeForPick(hold, UUID.randomUUID(), PICKER)))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
            assertThat(ledgerRows).isEmpty();
            verify(policies, never()).lock(any());
        }

        @Test
        @DisplayName("the operations endpoint deducts the same way and names the order in the ledger")
        void byHand() {
            UUID hold = UUID.randomUUID();
            StockPackage carton = cartonWithHold(BIN, 3, ReservationStatus.HELD, hold);
            UUID order = carton.findReservation(ReservationId.of(hold)).orElseThrow().orderId();

            service.consume(hold, PICKER);

            assertThat(carton.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(ledgerRows).singleElement().satisfies(row -> {
                assertThat(row.type()).isEqualTo(StockMovement.MovementType.PICK);
                assertThat(row.referenceType()).isEqualTo(StockMovement.ReferenceType.ORDER);
                assertThat(row.referenceId()).isEqualTo(order);
            });
            assertThat(refusal(() -> service.consume(hold, PICKER))).isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE);
            assertThat(ledgerRows).hasSize(1);
        }
    }

    @Nested
    @DisplayName("only an order's goods on a shelf are picked")
    class NotAnOrdersStock {

        @Test
        @DisplayName("an order's hold on goods a legacy print-shop carton still records (IN_PRODUCTION) is refused too")
        void goodsInThePrintShopAreRefused() {
            UUID holdId = UUID.randomUUID();
            StockPackage carton = carton("LPN-20261113-000009", CUP, new LocationId("HCM-PRD01"), 2,
                    StockStatus.IN_PRODUCTION, ReservationStatus.HELD, holdId);

            assertThat(refusal(() -> service.consumeForPick(holdId, UUID.randomUUID(), PICKER)))
                    .isEqualTo(ErrorCode.PRODUCTION_HOLD_NOT_PICKABLE);
            assertThat(carton.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(ledgerRows).isEmpty();
        }
    }

    @Nested
    @DisplayName("a short pick")
    class ShortPick {

        @Test
        @DisplayName("the recount is of the carton the hold is on, booked as the books have it")
        void theHeldCartonIsRecounted() {
            UUID holdId = UUID.randomUUID();
            StockPackage carton = cartonWithHold(BIN, 4, ReservationStatus.HELD, holdId);
            when(cycleCounts.findHolding(any())).thenReturn(Optional.empty());
            when(cycleCounts.booked(any())).thenReturn(true);
            when(cycleCounts.nextNumber(any())).thenReturn("CC-20261014-0001");
            when(cycleCounts.save(any())).thenAnswer(call -> call.getArgument(0));
            when(locations.warehouseOf(BIN)).thenReturn(Optional.of(UUID.randomUUID()));

            var recount = service.requestRecount(holdId, UUID.randomUUID(), 1);

            var slot = org.mockito.ArgumentCaptor.forClass(com.stockflow.inventory.internal.domain.CountSlot.class);
            verify(cycleCounts).findHolding(slot.capture());
            assertThat(slot.getValue().stockPackageId()).isEqualTo(carton.id());
            assertThat(slot.getValue().lotNumber()).as("the lot is the carton's").isEqualTo("LOT-1");
            var counts = org.mockito.ArgumentCaptor.forClass(com.stockflow.inventory.internal.domain.CycleCount.class);
            verify(cycleCounts).save(counts.capture());
            assertThat(counts.getValue().lines().get(0).slot().stockPackageId()).isEqualTo(carton.id());
            assertThat(counts.getValue().lines().get(0).booked()).isTrue();
            assertThat(recount.created()).isTrue();
        }
    }

    @Nested
    @DisplayName("putting a carton back (07 BR-05, section 4.5 step 14, team plan 4C-4)")
    class PuttingBack {

        private static final String LPN = "LPN-20261113-000001";

        private PutBackCommand command(UUID line, UUID hold, LocationId target) {
            return new PutBackCommand(line, hold, LPN, target.code(), PICKER);
        }

        /**
         * A carton of {@code count} units picked for an order: CONSUMED, with no stock status, its allocation
         * CONSUMED - as a pick leaves it.
         */
        private StockPackage pickedCarton(LocationId bin, int count, UUID hold) {
            return carton(LPN, CUP, bin, count, null, ReservationStatus.CONSUMED, hold);
        }

        @Test
        @DisplayName("back to the bin it left: CONSUMED to IN_STOCK sealed, AVAILABLE again, its lot and FIFO date kept, one PUT_BACK row naming the carton")
        void backToTheSameBin() {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            StockPackage picked = pickedCarton(BIN, 3, hold);

            service.putBack(command(line, hold, BIN));

            assertThat(picked.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(picked.closedAt()).isNull();
            assertThat(picked.location()).isEqualTo(BIN);
            assertThat(picked.lpn()).as("it keeps its LPN").isEqualTo(LPN);
            assertThat(picked.unitsInStock()).isEqualTo(3);
            assertThat(picked.stockStatus()).as("the status the put-back gives it: sellable again")
                    .isEqualTo(StockStatus.AVAILABLE);
            assertThat(picked.isFree()).isTrue();
            assertThat(picked.lotId()).as("the same goods").isEqualTo(LOT);
            assertThat(picked.receivedAt()).as("as old as they were: FIFO keeps their age").isEqualTo(RECEIVED);
            assertThat(writtenAtOnce).containsExactly(picked);
            assertThat(ledgerRows).singleElement().satisfies(row -> {
                assertThat(row.type()).isEqualTo(StockMovement.MovementType.PUT_BACK);
                assertThat(row.referenceType()).isEqualTo(StockMovement.ReferenceType.PICK_LINE);
                assertThat(row.referenceId()).isEqualTo(line);
                assertThat(row.from()).isNull();
                assertThat(row.to()).isEqualTo(BIN);
                assertThat(row.toStatus()).isEqualTo(StockStatus.AVAILABLE);
                assertThat(row.lotNumber()).isEqualTo("LOT-1");
                assertThat(row.stockPackage()).as("the ledger names the carton").isEqualTo(picked.id());
            });
            verify(policies).lock("LY-GIAY-DEMO-12OZ-XANH");
        }

        @Test
        @DisplayName("onto another bin: the same carton stands there whole; nothing is created or split")
        void ontoAnotherBin() {
            UUID hold = UUID.randomUUID();
            StockPackage picked = pickedCarton(BIN, 3, hold);

            service.putBack(command(UUID.randomUUID(), hold, OTHER_BIN));

            assertThat(picked.location()).isEqualTo(OTHER_BIN);
            assertThat(picked.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(picked.unitsInStock()).isEqualTo(3);
            assertThat(picked.sku()).isEqualTo(CUP);
            assertThat(picked.lotNumber()).isEqualTo("LOT-1");
            assertThat(writtenAtOnce).containsExactly(picked);
            assertThat(ledgerRows).singleElement().satisfies(row -> {
                assertThat(row.to()).isEqualTo(OTHER_BIN);
                assertThat(row.stockPackage()).isEqualTo(picked.id());
            });
        }

        private final LocationId quarantine = new LocationId("HCM-QC01");

        @Test
        @DisplayName("a damaged carton (removed at the check station) goes whole into a QUARANTINE area: IN_STOCK there, "
                + "QUARANTINE, one PUT_BACK row whose status is QUARANTINE - never AVAILABLE (4C-6)")
        void damagedCartonToQuarantine() {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            StockPackage picked = pickedCarton(BIN, 3, hold);
            when(locations.isQuarantineArea(quarantine)).thenReturn(true);
            UUID warehouse = UUID.randomUUID();
            when(locations.warehouseOf(BIN)).thenReturn(Optional.of(warehouse));
            when(locations.warehouseOf(quarantine)).thenReturn(Optional.of(warehouse));

            service.putBackToQuarantine(command(line, hold, quarantine));

            assertThat(picked.status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(picked.location()).isEqualTo(quarantine);
            assertThat(picked.lpn()).isEqualTo(LPN);
            assertThat(picked.stockStatus()).isEqualTo(StockStatus.QUARANTINE);
            assertThat(picked.unitsInStock()).isEqualTo(3);
            assertThat(picked.unitsAvailable()).as("out of ATP until a manager releases it").isZero();
            assertThat(ledgerRows).singleElement().satisfies(row -> {
                assertThat(row.type()).isEqualTo(StockMovement.MovementType.PUT_BACK);
                assertThat(row.referenceId()).isEqualTo(line);
                assertThat(row.to()).isEqualTo(quarantine);
                assertThat(row.toStatus()).isEqualTo(StockStatus.QUARANTINE);
                assertThat(row.stockPackage()).isEqualTo(picked.id());
            });
            var order = org.mockito.Mockito.inOrder(policies, stockPackages);
            order.verify(policies).lock("LY-GIAY-DEMO-12OZ-XANH");
            order.verify(stockPackages).findByIdForUpdate(picked.id());
        }

        @Test
        @DisplayName("a bin is not a quarantine area: LOCATION_AREA_MISMATCH, and the carton stays picked")
        void damagedCartonNeedsAQuarantineArea() {
            UUID hold = UUID.randomUUID();
            StockPackage picked = pickedCarton(BIN, 3, hold);
            when(locations.isQuarantineArea(OTHER_BIN)).thenReturn(false);

            assertThat(refusal(() -> service.putBackToQuarantine(command(UUID.randomUUID(), hold, OTHER_BIN))))
                    .isEqualTo(ErrorCode.LOCATION_AREA_MISMATCH);

            assertThat(picked.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(picked.unitsInStock()).isZero();
            assertThat(ledgerRows).isEmpty();
            verify(stockPackages, never()).save(any());
            verify(stockPackages, never()).saveNow(any());
        }

        @Test
        @DisplayName("a quarantine area of another warehouse is MOVE_ACROSS_WAREHOUSES: stock never changes warehouse")
        void damagedCartonStaysInItsWarehouse() {
            UUID hold = UUID.randomUUID();
            StockPackage picked = pickedCarton(BIN, 3, hold);
            when(locations.isQuarantineArea(quarantine)).thenReturn(true);
            when(locations.warehouseOf(BIN)).thenReturn(Optional.of(UUID.randomUUID()));
            when(locations.warehouseOf(quarantine)).thenReturn(Optional.of(UUID.randomUUID()));

            assertThat(refusal(() -> service.putBackToQuarantine(command(UUID.randomUUID(), hold, quarantine))))
                    .isEqualTo(ErrorCode.MOVE_ACROSS_WAREHOUSES);

            assertThat(picked.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(ledgerRows).isEmpty();
        }

        @Test
        @DisplayName("a quarantine area whose warehouse cannot be resolved is LOCATION_NOT_FOUND; two unknown places never pass "
                + "as 'the same warehouse'")
        void unknownTargetWarehouse() {
            UUID hold = UUID.randomUUID();
            StockPackage picked = pickedCarton(BIN, 3, hold);
            when(locations.isQuarantineArea(quarantine)).thenReturn(true);
            when(locations.warehouseOf(BIN)).thenReturn(Optional.empty());
            when(locations.warehouseOf(quarantine)).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.putBackToQuarantine(command(UUID.randomUUID(), hold, quarantine))))
                    .isEqualTo(ErrorCode.LOCATION_NOT_FOUND);

            assertThat(picked.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(ledgerRows).isEmpty();
        }

        @Test
        @DisplayName("a carton standing at a location with no warehouse is a data fault: IllegalStateException, nothing changes")
        void cartonAtAnUnknownLocation() {
            UUID hold = UUID.randomUUID();
            StockPackage picked = pickedCarton(BIN, 3, hold);
            when(locations.isQuarantineArea(quarantine)).thenReturn(true);
            when(locations.warehouseOf(quarantine)).thenReturn(Optional.of(UUID.randomUUID()));
            when(locations.warehouseOf(BIN)).thenReturn(Optional.empty());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.putBackToQuarantine(
                    command(UUID.randomUUID(), hold, quarantine))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(LPN);

            assertThat(picked.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(ledgerRows).isEmpty();
        }

        @Test
        @DisplayName("an unknown or unusable quarantine area is refused before the area rule; the same line again changes nothing")
        void damagedCartonReplayAndUsability() {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            StockPackage picked = pickedCarton(BIN, 3, hold);
            LocationId closed = new LocationId("HCM-QC09");
            when(locations.stateOf(closed)).thenReturn(LocationDirectory.LocationState.UNUSABLE);
            assertThat(refusal(() -> service.putBackToQuarantine(command(line, hold, closed))))
                    .isEqualTo(ErrorCode.LOCATION_NOT_USABLE);

            when(locations.isQuarantineArea(quarantine)).thenReturn(true);
            UUID warehouse = UUID.randomUUID();
            when(locations.warehouseOf(BIN)).thenReturn(Optional.of(warehouse));
            when(locations.warehouseOf(quarantine)).thenReturn(Optional.of(warehouse));
            service.putBackToQuarantine(command(line, hold, quarantine));
            service.putBackToQuarantine(command(line, hold, quarantine));

            assertThat(ledgerRows).hasSize(1);
            assertThat(picked.stockStatus()).isEqualTo(StockStatus.QUARANTINE);
            verify(stockPackages, org.mockito.Mockito.times(1)).saveNow(any());
        }

        @Test
        @DisplayName("the SKU is locked, then the carton; the carton is written before the ledger line names it at its new place")
        void lockOrder() {
            UUID hold = UUID.randomUUID();
            StockPackage picked = pickedCarton(BIN, 3, hold);

            service.putBack(command(UUID.randomUUID(), hold, OTHER_BIN));

            var order = org.mockito.Mockito.inOrder(policies, stockPackages);
            order.verify(policies).lock("LY-GIAY-DEMO-12OZ-XANH");
            order.verify(stockPackages).findByIdForUpdate(picked.id());
            order.verify(stockPackages).saveNow(picked);
            // check_stock_movement_package reads the carton when the line is inserted: a put-back onto another bin
            // failed 409 when the line went first.
            assertThat(writtenAtOnceBeforeLine).singleElement().satisfies(written ->
                    assertThat(written).containsExactly(picked));
        }

        @Test
        @DisplayName("the same line again changes nothing: one ledger row, the carton is written once")
        void idempotentPerLine() {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            StockPackage picked = pickedCarton(BIN, 3, hold);
            service.putBack(command(line, hold, BIN));

            service.putBack(command(line, hold, OTHER_BIN));

            assertThat(picked.unitsInStock()).isEqualTo(3);
            assertThat(picked.location()).as("the replay is not a second move").isEqualTo(BIN);
            assertThat(ledgerRows).hasSize(1);
            assertThat(saved).isEmpty();
            verify(stockPackages, org.mockito.Mockito.times(1)).saveNow(any());
        }

        @Test
        @DisplayName("an unknown or unusable bin is refused before any stock changes")
        void unusableBin() {
            UUID hold = UUID.randomUUID();
            StockPackage picked = pickedCarton(BIN, 3, hold);
            when(locations.stateOf(OTHER_BIN)).thenReturn(LocationDirectory.LocationState.UNUSABLE);
            LocationId nowhere = new LocationId("HCM-ZZ9-9-Z");
            when(locations.stateOf(nowhere)).thenReturn(LocationDirectory.LocationState.UNKNOWN);

            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, OTHER_BIN))))
                    .isEqualTo(ErrorCode.LOCATION_NOT_USABLE);
            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, nowhere))))
                    .isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
            assertThat(picked.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(picked.unitsInStock()).isZero();
            assertThat(ledgerRows).isEmpty();
            assertThat(saved).isEmpty();
            verify(stockPackages, never()).saveNow(any());
        }

        @Test
        @DisplayName("a hold the pick did not consume is not a carton that left: STOCK_PACKAGE_TAKEN_WHOLE")
        void notConsumed() {
            UUID hold = UUID.randomUUID();
            StockPackage held = carton(LPN, CUP, BIN, 3, StockStatus.AVAILABLE, ReservationStatus.HELD, hold);

            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, BIN))))
                    .isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(held.isHeld()).isTrue();
            assertThat(ledgerRows).isEmpty();
            verify(stockPackages, never()).saveNow(any());
        }

        @Test
        @DisplayName("a carton that is in stock already does not come back a second time: INVALID_STOCK_PACKAGE_TRANSITION")
        void alreadyBackInStock() {
            UUID hold = UUID.randomUUID();
            // Picked, then brought back by another document (a return receipt put it into quarantine).
            StockPackage back = carton(LPN, CUP, BIN, 3, StockStatus.QUARANTINE, ReservationStatus.CONSUMED, hold);

            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, BIN))))
                    .isEqualTo(ErrorCode.INVALID_STOCK_PACKAGE_TRANSITION);
            assertThat(back.stockStatus()).as("a put-back is not how quarantined goods get on sale")
                    .isEqualTo(StockStatus.QUARANTINE);
            assertThat(ledgerRows).isEmpty();
            verify(stockPackages, never()).saveNow(any());
        }

        @Test
        @DisplayName("a command naming another LPN than the hold's carton is a caller's bug: IllegalStateException")
        void anotherLpnIsACallersBug() {
            UUID hold = UUID.randomUUID();
            StockPackage picked = pickedCarton(BIN, 3, hold);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.putBack(new PutBackCommand(
                    UUID.randomUUID(), hold, "LPN-SOMEONE-ELSE", BIN.code(), PICKER)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(picked.status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(ledgerRows).isEmpty();
        }

        @Test
        @DisplayName("the LPN is compared in any case")
        void lpnInAnyCase() {
            UUID hold = UUID.randomUUID();
            StockPackage picked = pickedCarton(BIN, 3, hold);

            service.putBack(new PutBackCommand(UUID.randomUUID(), hold, " " + LPN.toLowerCase() + " ", BIN.code(),
                    PICKER));

            assertThat(picked.status()).isEqualTo(StockPackageStatus.IN_STOCK);
        }

        @Test
        @DisplayName("an unknown hold is RESERVATION_NOT_FOUND")
        void unknownHold() {
            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), UUID.randomUUID(), BIN))))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("staging a carton, deducting it from the staging area, putting it back (team plan 4C-5)")
    class Staging {

        private static final String LPN = "LPN-20261113-000001";
        private final LocationId staging = new LocationId("HCM-STG01");

        private record Held(StockPackage carton, UUID hold) { }

        @BeforeEach
        void stagingIsAnArea() {
            when(locations.isStagingArea(staging)).thenReturn(true);
            when(locations.stateOf(staging)).thenReturn(LocationDirectory.LocationState.USABLE);
        }

        private StageCommand stageCommand(UUID line, UUID hold) {
            return new StageCommand(line, hold, LPN, staging.code(), PICKER);
        }

        /** A carton of {@code count} units sealed in {@code bin}, with one allocation of it in {@code status}. */
        private Held heldCarton(LocationId bin, int count, ReservationStatus status, StockStatus stockStatus) {
            UUID hold = UUID.randomUUID();
            return new Held(carton(LPN, CUP, bin, count, stockStatus, status, hold), hold);
        }

        private Held heldCarton(LocationId bin) {
            return heldCarton(bin, 6, ReservationStatus.HELD, StockStatus.AVAILABLE);
        }

        @Test
        @DisplayName("stage: the carton moves whole to the staging area, still AVAILABLE, the hold stays HELD, one MOVE row names the line")
        void stagesWhole() {
            Held held = heldCarton(BIN);
            UUID line = UUID.randomUUID();

            StagedPick staged = service.stage(stageCommand(line, held.hold()));

            assertThat(staged).isEqualTo(new StagedPick(held.hold(), LPN, "LY-GIAY-DEMO-12OZ-XANH", 6, BIN.code(),
                    staging.code(), false));
            assertThat(held.carton().location()).isEqualTo(staging);
            assertThat(held.carton().status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(held.carton().stockStatus()).as("a held carton stays AVAILABLE").isEqualTo(StockStatus.AVAILABLE);
            assertThat(held.carton().unitsInStock()).as("nothing is deducted").isEqualTo(6);
            assertThat(held.carton().findReservation(ReservationId.of(held.hold())).orElseThrow().status())
                    .isEqualTo(ReservationStatus.HELD);
            assertThat(saved).containsExactly(held.carton());
            assertThat(ledgerRows).singleElement().satisfies(row -> {
                assertThat(row.type()).isEqualTo(StockMovement.MovementType.MOVE);
                assertThat(row.referenceType()).isEqualTo(StockMovement.ReferenceType.PICK_LINE);
                assertThat(row.referenceId()).isEqualTo(line);
                assertThat(row.from()).isEqualTo(BIN);
                assertThat(row.to()).isEqualTo(staging);
                assertThat(row.stockPackage()).isEqualTo(held.carton().id());
            });
            verify(publisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("stage: SKU lock, then the carton - in that order")
        void stageLockOrder() {
            Held held = heldCarton(BIN);

            service.stage(stageCommand(UUID.randomUUID(), held.hold()));

            var order = org.mockito.Mockito.inOrder(policies, stockPackages);
            order.verify(policies).lock("LY-GIAY-DEMO-12OZ-XANH");
            order.verify(stockPackages).findByIdForUpdate(held.carton().id());
        }

        @Test
        @DisplayName("stage: the same line again changes nothing and says it was staged already")
        void stageReplay() {
            Held held = heldCarton(BIN);
            UUID line = UUID.randomUUID();
            service.stage(stageCommand(line, held.hold()));
            saved.clear();

            StagedPick again = service.stage(stageCommand(line, held.hold()));

            assertThat(again.alreadyStaged()).isTrue();
            assertThat(again.quantity()).as("read from the carton the line names: the ledger keeps no number")
                    .isEqualTo(6);
            assertThat(again.stagingLocationCode()).isEqualTo(staging.code());
            assertThat(again.fromLocationCode()).isEqualTo(BIN.code());
            assertThat(ledgerRows).hasSize(1);
            assertThat(saved).isEmpty();
            verify(stockPackages, org.mockito.Mockito.times(1)).save(any());
        }

        @Test
        @DisplayName("stage: a hold that is not HELD is RESERVATION_NOT_ACTIVE; a carton that is not AVAILABLE is STOCK_STATUS_MISMATCH")
        void stageHoldAndCartonState() {
            for (ReservationStatus status : List.of(ReservationStatus.CONSUMED, ReservationStatus.RELEASED)) {
                Held notHeld = heldCarton(BIN, 6, status, StockStatus.AVAILABLE);
                assertThat(refusal(() -> service.stage(stageCommand(UUID.randomUUID(), notHeld.hold()))))
                        .as(status.name()).isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE);
            }
            // A state the table refuses (a held carton stays AVAILABLE); the service says so too, with its own code.
            Held quarantined = heldCarton(BIN, 6, ReservationStatus.HELD, StockStatus.QUARANTINE);
            assertThat(refusal(() -> service.stage(stageCommand(UUID.randomUUID(), quarantined.hold()))))
                    .isEqualTo(ErrorCode.STOCK_STATUS_MISMATCH);
            assertThat(quarantined.carton().location()).isEqualTo(BIN);
            assertThat(ledgerRows).isEmpty();
            verify(stockPackages, never()).save(any());
        }

        @Test
        @DisplayName("stage: a target that is a bin, unusable, unknown or in another warehouse is refused before anything moves")
        void stageTargets() {
            Held held = heldCarton(BIN);
            when(locations.stateOf(OTHER_BIN)).thenReturn(LocationDirectory.LocationState.USABLE);
            when(locations.isStagingArea(OTHER_BIN)).thenReturn(false);
            assertThat(refusal(() -> service.stage(new StageCommand(UUID.randomUUID(), held.hold(), LPN,
                    OTHER_BIN.code(), PICKER)))).as("a bin").isEqualTo(ErrorCode.STAGING_AREA_INVALID);

            LocationId closed = new LocationId("HCM-STG02");
            when(locations.stateOf(closed)).thenReturn(LocationDirectory.LocationState.UNUSABLE);
            assertThat(refusal(() -> service.stage(new StageCommand(UUID.randomUUID(), held.hold(), LPN,
                    closed.code(), PICKER)))).as("unusable").isEqualTo(ErrorCode.LOCATION_NOT_USABLE);

            LocationId nowhere = new LocationId("HCM-STG09");
            when(locations.stateOf(nowhere)).thenReturn(LocationDirectory.LocationState.UNKNOWN);
            assertThat(refusal(() -> service.stage(new StageCommand(UUID.randomUUID(), held.hold(), LPN,
                    nowhere.code(), PICKER)))).as("unknown").isEqualTo(ErrorCode.LOCATION_NOT_FOUND);

            LocationId hanoi = new LocationId("HN-STG01");
            when(locations.stateOf(hanoi)).thenReturn(LocationDirectory.LocationState.USABLE);
            when(locations.isStagingArea(hanoi)).thenReturn(true);
            assertThat(refusal(() -> service.stage(new StageCommand(UUID.randomUUID(), held.hold(), LPN,
                    hanoi.code(), PICKER)))).as("another warehouse").isEqualTo(ErrorCode.MOVE_ACROSS_WAREHOUSES);

            assertThat(held.carton().location()).isEqualTo(BIN);
            assertThat(ledgerRows).isEmpty();
        }

        @Test
        @DisplayName("stage: a command naming another LPN than the hold carton is a bug of the caller")
        void stageAnotherLpn() {
            Held held = heldCarton(BIN);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.stage(new StageCommand(
                    UUID.randomUUID(), held.hold(), "LPN-SOMEONE-ELSE", staging.code(), PICKER)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(held.carton().location()).isEqualTo(BIN);
        }

        /** A carton staged by {@code line}: moved, MOVE row written. */
        private Held staged(UUID line) {
            Held held = heldCarton(BIN);
            service.stage(stageCommand(line, held.hold()));
            saved.clear();
            return held;
        }

        @Test
        @DisplayName("consumeStaged: deducts from the staging area - hold CONSUMED, carton CONSUMED, one PICK row from staging")
        void consumeStagedDeducts() {
            UUID line = UUID.randomUUID();
            Held held = staged(line);

            ConsumedPick consumed = service.consumeStaged(held.hold(), line, PICKER);

            assertThat(consumed).isEqualTo(new ConsumedPick(held.hold(), "LY-GIAY-DEMO-12OZ-XANH", staging.code(),
                    "LOT-1", 6, false));
            assertThat(held.carton().unitsInStock()).isZero();
            assertThat(held.carton().findReservation(ReservationId.of(held.hold())).orElseThrow().status())
                    .isEqualTo(ReservationStatus.CONSUMED);
            assertThat(held.carton().status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(ledgerRows).hasSize(2);
            assertThat(ledgerRows.get(1)).satisfies(row -> {
                assertThat(row.type()).isEqualTo(StockMovement.MovementType.PICK);
                assertThat(row.referenceType()).isEqualTo(StockMovement.ReferenceType.PICK_LINE);
                assertThat(row.referenceId()).isEqualTo(line);
                assertThat(row.from()).isEqualTo(staging);
                assertThat(row.to()).isNull();
                assertThat(row.stockPackage()).as("the line is the carton, whole").isEqualTo(held.carton().id());
            });
        }

        @Test
        @DisplayName("consumeStaged: the same line again changes nothing and says it was consumed")
        void consumeStagedReplay() {
            UUID line = UUID.randomUUID();
            Held held = staged(line);
            service.consumeStaged(held.hold(), line, PICKER);

            ConsumedPick again = service.consumeStaged(held.hold(), line, PICKER);

            assertThat(again.alreadyConsumed()).isTrue();
            assertThat(ledgerRows).hasSize(2);
        }

        @Test
        @DisplayName("consumeStaged: a line that never staged the carton is a bug of the caller, nothing is deducted")
        void consumeNotStaged() {
            Held held = heldCarton(BIN);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            service.consumeStaged(held.hold(), UUID.randomUUID(), PICKER))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(held.carton().unitsInStock()).isEqualTo(6);
            assertThat(held.carton().isHeld()).isTrue();
            assertThat(ledgerRows).isEmpty();
        }

        /** The order was cancelled: its hold is released, the carton is still in the staging area. */
        private void cancelled(Held held) {
            held.carton().releaseReservation(ReservationId.of(held.hold()), ReleaseReason.ORDER_CANCELLED, NOW);
        }

        @Test
        @DisplayName("unstage: back onto a bin - the carton moves whole, one second MOVE row")
        void unstageMovesBack() {
            UUID line = UUID.randomUUID();
            Held held = staged(line);
            cancelled(held);

            service.unstage(new PutBackCommand(line, held.hold(), LPN, OTHER_BIN.code(), PICKER));

            assertThat(held.carton().location()).isEqualTo(OTHER_BIN);
            assertThat(held.carton().status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(held.carton().unitsInStock()).isEqualTo(6);
            assertThat(held.carton().isFree()).as("released and on a bin: stock anyone may be given").isTrue();
            assertThat(ledgerRows).hasSize(2);
            assertThat(ledgerRows.get(1)).satisfies(row -> {
                assertThat(row.type()).isEqualTo(StockMovement.MovementType.MOVE);
                assertThat(row.referenceId()).isEqualTo(line);
                assertThat(row.from()).isEqualTo(staging);
                assertThat(row.to()).isEqualTo(OTHER_BIN);
                assertThat(row.stockPackage()).isEqualTo(held.carton().id());
            });
        }

        @Test
        @DisplayName("unstage: the line again changes nothing (its second MOVE is the replay)")
        void unstageReplay() {
            UUID line = UUID.randomUUID();
            Held held = staged(line);
            cancelled(held);
            service.unstage(new PutBackCommand(line, held.hold(), LPN, OTHER_BIN.code(), PICKER));

            service.unstage(new PutBackCommand(line, held.hold(), LPN, BIN.code(), PICKER));

            assertThat(held.carton().location()).isEqualTo(OTHER_BIN);
            assertThat(ledgerRows).hasSize(2);
        }

        @Test
        @DisplayName("unstage: a hold still HELD is CONFLICT - the order owns the carton until it is released")
        void unstageWhileHeld() {
            UUID line = UUID.randomUUID();
            Held held = staged(line);

            assertThat(refusal(() -> service.unstage(new PutBackCommand(line, held.hold(), LPN, OTHER_BIN.code(),
                    PICKER)))).isEqualTo(ErrorCode.CONFLICT);
            assertThat(held.carton().location()).isEqualTo(staging);
            assertThat(ledgerRows).hasSize(1);
        }

        @Test
        @DisplayName("unstage: onto a staging area is STAGING_MOVE_NOT_ALLOWED; a line that never staged is a bug of the caller")
        void unstageTargets() {
            UUID line = UUID.randomUUID();
            Held held = staged(line);
            cancelled(held);
            LocationId otherStaging = new LocationId("HCM-STG02");
            when(locations.stateOf(otherStaging)).thenReturn(LocationDirectory.LocationState.USABLE);
            when(locations.isStagingArea(otherStaging)).thenReturn(true);

            assertThat(refusal(() -> service.unstage(new PutBackCommand(line, held.hold(), LPN,
                    otherStaging.code(), PICKER)))).isEqualTo(ErrorCode.STAGING_MOVE_NOT_ALLOWED);
            assertThat(refusal(() -> service.unstage(new PutBackCommand(line, held.hold(), LPN, staging.code(),
                    PICKER)))).as("the staging area itself").isEqualTo(ErrorCode.STAGING_MOVE_NOT_ALLOWED);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.unstage(new PutBackCommand(
                    UUID.randomUUID(), held.hold(), LPN, OTHER_BIN.code(), PICKER)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(held.carton().location()).isEqualTo(staging);
            assertThat(ledgerRows).hasSize(1);
        }

        @Test
        @DisplayName("unstage: the carton must fit the bin, or go to OVERFLOW when no bin takes it (07 BR-12): a "
                + "refusal moves nothing, and the target is judged by the placement rule")
        void unstageChecksTheFit() {
            UUID line = UUID.randomUUID();
            Held held = staged(line);
            cancelled(held);
            org.mockito.Mockito.doThrow(CartonPlacement.doesNotFit(LPN, OTHER_BIN.code()))
                    .when(placement).requirePutBackTarget(org.mockito.ArgumentMatchers.eq(OTHER_BIN.code()), any());

            assertThat(refusal(() -> service.unstage(new PutBackCommand(line, held.hold(), LPN, OTHER_BIN.code(),
                    PICKER)))).isEqualTo(ErrorCode.CARTON_DOES_NOT_FIT_BIN);
            assertThat(held.carton().location()).as("nothing moved").isEqualTo(staging);
            assertThat(ledgerRows).hasSize(1);

            LocationId overflow = new LocationId("HCM-OVF01");
            when(locations.stateOf(overflow)).thenReturn(LocationDirectory.LocationState.USABLE);
            when(locations.warehouseOf(any())).thenReturn(Optional.of(UUID.randomUUID()));
            service.unstage(new PutBackCommand(line, held.hold(), LPN, overflow.code(), PICKER));

            verify(placement).requirePutBackTarget(org.mockito.ArgumentMatchers.eq(overflow.code()), any());
            assertThat(held.carton().location()).as("the placement rule let OVERFLOW through").isEqualTo(overflow);
        }

        @Test
        @DisplayName("overflowAreasFor: the OVERFLOW areas of the warehouse for the cartons no bin takes, none for one a bin takes")
        void overflowAreasForTheCartonsNoBinTakes() {
            Held held = staged(UUID.randomUUID());
            UUID warehouse = UUID.randomUUID();
            when(stockPackages.findByLpns(any())).thenReturn(List.of(held.carton()));
            when(placement.fittingNoBin(org.mockito.ArgumentMatchers.eq(warehouse), any()))
                    .thenReturn(java.util.Set.of(held.carton().id().value()));
            when(placement.overflowAreas(warehouse)).thenReturn(List.of("HCM-OVF01"));

            assertThat(service.overflowAreasFor(warehouse, List.of(LPN)))
                    .containsOnlyKeys(held.carton().lpn()).containsValue(List.of("HCM-OVF01"));

            when(placement.fittingNoBin(org.mockito.ArgumentMatchers.eq(warehouse), any()))
                    .thenReturn(java.util.Set.of());
            assertThat(service.overflowAreasFor(warehouse, List.of(LPN))).isEmpty();
            assertThat(service.overflowAreasFor(warehouse, List.of())).isEmpty();
        }
    }


    @Nested
    @DisplayName("the whole task moves to dispatch in one call (review M1)")
    class BulkMoveToDispatch {

        private final LocationId staging = new LocationId("HCM-STG01");

        private record Line(StockPackage carton, UUID hold, UUID line, StagedLine staged) { }

        @BeforeEach
        void stubs() {
            when(locations.isStagingArea(staging)).thenReturn(true);
            when(locations.stateOf(staging)).thenReturn(LocationDirectory.LocationState.USABLE);
        }

        /** A sealed carton of {@code sku} held for an order and staged by its own pick line. */
        private Line stagedLine(String sku, String lpn, LocationId bin) {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            StockPackage carton = carton(lpn, new Sku(sku), bin, 2, StockStatus.AVAILABLE, ReservationStatus.HELD,
                    hold);
            service.stage(new StageCommand(line, hold, lpn, staging.code(), PICKER));
            saved.clear();
            return new Line(carton, hold, line, new StagedLine(hold, line));
        }

        @Test
        @DisplayName("lines of two SKUs given in reverse order: SKU locks sorted, then every carton locked in one call, each taken out of stock once")
        void locksInOneOrderForTheWholeTask() {
            Line b = stagedLine("SKU-B", "LPN-B", BIN);
            Line a = stagedLine("SKU-A", "LPN-A", OTHER_BIN);
            org.mockito.Mockito.clearInvocations(policies, stockPackages);

            var consumed = service.consumeStaged(List.of(b.staged(), a.staged()), PICKER);

            assertThat(consumed).extracting(ConsumedPick::sku).containsExactly("SKU-B", "SKU-A");
            assertThat(consumed).allMatch(c -> !c.alreadyConsumed());
            var order = org.mockito.Mockito.inOrder(policies, stockPackages);
            order.verify(policies).lock("SKU-A");
            order.verify(policies).lock("SKU-B");
            // one call for the task's cartons, which the repository locks in id order - never one lock per line, in
            // the order the lines were given
            order.verify(stockPackages).findAllByIdForUpdate(any());
            verify(stockPackages, org.mockito.Mockito.times(1)).findAllByIdForUpdate(any());
            verify(stockPackages, never()).findByIdForUpdate(any());
            assertThat(a.carton().status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(b.carton().status()).isEqualTo(StockPackageStatus.CONSUMED);
            verify(stockPackages, org.mockito.Mockito.times(2)).save(any());
            assertThat(ledgerRows.stream().filter(r -> r.type() == StockMovement.MovementType.PICK)).hasSize(2)
                    .allMatch(r -> r.from().equals(staging));
        }

        @Test
        @DisplayName("the same call again changes nothing: every line answers 'already'")
        void replayChangesNothing() {
            Line b = stagedLine("SKU-B", "LPN-B", BIN);
            Line a = stagedLine("SKU-A", "LPN-A", OTHER_BIN);
            service.consumeStaged(List.of(b.staged(), a.staged()), PICKER);
            int ledger = ledgerRows.size();
            org.mockito.Mockito.clearInvocations(stockPackages);

            var again = service.consumeStaged(List.of(b.staged(), a.staged()), PICKER);

            assertThat(again).allMatch(ConsumedPick::alreadyConsumed);
            assertThat(ledgerRows).hasSize(ledger);
            verify(stockPackages, never()).save(any());
        }

        @Test
        @DisplayName("a carton a count found LOST, or one that is elsewhere, is CONFLICT: the task is refused")
        void aCartonNotWhereItWasStagedRefusesTheWholeTask() {
            Line lost = stagedLine("SKU-B", "LPN-B", BIN);
            Line good = stagedLine("SKU-A", "LPN-A", OTHER_BIN);
            // An approved count's shortage: the hold is given up with the carton, which is LOST.
            lost.carton().giveUpHoldsOfWrittenOffCarton(UUID.randomUUID(), NOW);
            lost.carton().lose(NOW);
            org.mockito.Mockito.clearInvocations(stockPackages);

            assertThat(refusal(() -> service.consumeStaged(List.of(lost.staged(), good.staged()), PICKER)))
                    .isEqualTo(ErrorCode.CONFLICT);
            // the refusal rolls the transaction back; the line after it was never reached
            verify(stockPackages, never()).save(any());
            assertThat(good.carton().status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(lost.carton().status()).as("never deducted as if it had shipped")
                    .isEqualTo(StockPackageStatus.LOST);
            assertThat(refusal(() -> service.consumeStaged(List.of(good.staged(), lost.staged()), PICKER)))
                    .as("whatever the order of the lines").isEqualTo(ErrorCode.CONFLICT);

            ledgerRows.clear();   // a refusal rolled back in production; this mock ledger does not
            Line moved = stagedLine("SKU-C", "LPN-C", BIN);
            moved.carton().moveTo(OTHER_BIN);
            assertThat(refusal(() -> service.consumeStaged(List.of(moved.staged()), PICKER)))
                    .isEqualTo(ErrorCode.CONFLICT);
            assertThat(moved.carton().unitsInStock()).isEqualTo(2);
            assertThat(ledgerRows.stream().filter(r -> r.type() == StockMovement.MovementType.PICK)).isEmpty();
        }
    }
}
