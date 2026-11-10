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
import com.stockflow.inventory.internal.domain.Quantity;
import com.stockflow.inventory.internal.domain.ReleaseReason;
import com.stockflow.inventory.internal.domain.Reservation;
import com.stockflow.inventory.internal.domain.ReservationId;
import com.stockflow.inventory.internal.domain.ReservationStatus;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
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
import java.util.List;
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
 * database — the ledger is an in-memory list, the stock rows are mocks handing out real aggregates.
 */
class PickConsumptionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final Sku SOFA = new Sku("SOFA-3S-GREY");
    private static final LocationId BIN = new LocationId("HCM-A01-2-B");
    private static final LocationId OTHER_BIN = new LocationId("HCM-A02-1-A");
    private static final UUID PICKER = UUID.randomUUID();

    private final StockItemRepository stockItems = mock(StockItemRepository.class);
    private final List<StockMovement> ledgerRows = new ArrayList<>();
    private final StockMovementLog ledger = new StockMovementLog() {
        @Override
        public void append(StockMovement movement) {
            ledgerRows.add(movement);
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
        public Optional<StockMovement> findLooseByReference(StockMovement.MovementType type,
                                                            StockMovement.ReferenceType referenceType, UUID referenceId) {
            return ledgerRows.stream().filter(row -> row.fromStockPackage() == null && row.toStockPackage() == null)
                    .filter(row -> row.type() == type && row.referenceType() == referenceType
                            && row.referenceId().equals(referenceId)).findFirst();
        }

        @Override
        public java.util.Map<UUID, StockMovement> findLooseByReferences(StockMovement.MovementType type,
                StockMovement.ReferenceType referenceType, java.util.Collection<UUID> referenceIds) {
            java.util.Map<UUID, StockMovement> first = new java.util.HashMap<>();
            for (UUID id : referenceIds) {
                findLooseByReference(type, referenceType, id).ifPresent(row -> first.put(id, row));
            }
            return first;
        }

        @Override
        public int sumQuantityByReference(java.util.Collection<StockMovement.MovementType> types,
                                          StockMovement.ReferenceType referenceType, UUID referenceId) {
            return ledgerRows.stream().filter(row -> types.contains(row.type()) && row.referenceType() == referenceType
                    && row.referenceId().equals(referenceId)).mapToInt(StockMovement::quantity).sum();
        }
    };
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final LocationDirectory locations = mock(LocationDirectory.class);
    private final InventoryPolicyRepository policies = mock(InventoryPolicyRepository.class);
    private final com.stockflow.inventory.internal.domain.CycleCountRepository cycleCounts =
            mock(com.stockflow.inventory.internal.domain.CycleCountRepository.class);
    private final StockPackageRepository stockPackages = mock(StockPackageRepository.class);
    private final PickConsumptionService service = new PickConsumptionService(stockItems, ledger,
            new InventoryEventPublisher(publisher), locations, policies,
            new StockPackageContents(stockPackages, stockItems), Clock.fixed(NOW, ZoneOffset.UTC),
            cycleCounts, stockPackages);
    private final List<StockItem> saved = new ArrayList<>();
    private final List<StockItem> merged = new ArrayList<>();

    @BeforeEach
    void storeSaves() {
        when(stockItems.save(any())).thenAnswer(call -> {
            StockItem item = call.getArgument(0);
            saved.add(item);
            return item;
        });
        when(stockItems.saveMerged(any())).thenAnswer(call -> {
            StockItem item = call.getArgument(0);
            merged.add(item);
            return item;
        });
        when(locations.stateOf(any())).thenReturn(LocationDirectory.LocationState.USABLE);
    }

    private static ErrorCode refusal(ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return ((BusinessException) thrown).errorCode();
    }

    /** An AVAILABLE stock row at {@code location} holding {@code held} units for one pinned hold. */
    private StockItem stockWithHold(LocationId location, int onHand, int held, ReservationStatus status,
                                    UUID reservationId) {
        Reservation hold = new Reservation(ReservationId.of(reservationId), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), Quantity.of(held), NOW.minusSeconds(3600), null, status,
                status == ReservationStatus.RELEASED ? ReleaseReason.ORDER_CANCELLED : null,
                status == ReservationStatus.HELD ? null : NOW.minusSeconds(60));
        StockItem item = new StockItem(StockItemId.newId(), SOFA, location, "LOT-1", Quantity.of(onHand), StockStatus.AVAILABLE, List.of(hold), 0L, NOW.minusSeconds(86_400));
        when(stockItems.findByReservationIdForUpdate(ReservationId.of(reservationId))).thenReturn(Optional.of(item));
        when(stockItems.findByIdForUpdate(item.id())).thenReturn(Optional.of(item));
        return item;
    }

    @Nested
    @DisplayName("deducting a pick line")
    class Deducting {

        @Test
        @DisplayName("on_hand falls by the hold, the hold is consumed, one PICK ledger row names the line, StockDeducted goes out")
        void deductsOnce() {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            StockItem item = stockWithHold(BIN, 10, 3, ReservationStatus.HELD, hold);

            ConsumedPick consumed = service.consumeForPick(hold, line, PICKER);

            assertThat(consumed).isEqualTo(new ConsumedPick(hold, "SOFA-3S-GREY", "HCM-A01-2-B", "LOT-1", 3, false));
            assertThat(item.onHand()).isEqualTo(Quantity.of(7));
            assertThat(item.findReservation(ReservationId.of(hold)).orElseThrow().status())
                    .isEqualTo(ReservationStatus.CONSUMED);
            assertThat(saved).containsExactly(item);
            assertThat(ledgerRows).singleElement().satisfies(row -> {
                assertThat(row.type()).isEqualTo(StockMovement.MovementType.PICK);
                assertThat(row.referenceType()).isEqualTo(StockMovement.ReferenceType.PICK_LINE);
                assertThat(row.referenceId()).isEqualTo(line);
                assertThat(row.from()).isEqualTo(BIN);
                assertThat(row.to()).isNull();
                assertThat(row.quantity()).isEqualTo(3);
                assertThat(row.lotNumber()).isEqualTo("LOT-1");
                assertThat(row.status()).isEqualTo(StockStatus.AVAILABLE);
                assertThat(row.actorId()).isEqualTo(PICKER);
            });
            verify(publisher).publishEvent(new StockDeducted(
                    item.findReservation(ReservationId.of(hold)).orElseThrow().orderId(), "SOFA-3S-GREY",
                    "HCM-A01-2-B", 3));
        }

        @Test
        @DisplayName("the same line again changes nothing and says it was already consumed")
        void alreadyConsumedByThisLine() {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            StockItem item = stockWithHold(BIN, 10, 3, ReservationStatus.HELD, hold);
            service.consumeForPick(hold, line, PICKER);
            saved.clear();

            ConsumedPick again = service.consumeForPick(hold, line, PICKER);

            assertThat(again.alreadyConsumed()).isTrue();
            assertThat(again.quantity()).isEqualTo(3);
            assertThat(item.onHand()).isEqualTo(Quantity.of(7));
            assertThat(ledgerRows).hasSize(1);
            assertThat(saved).isEmpty();
        }

        @Test
        @DisplayName("a hold consumed by another line is not active: refused, nothing written")
        void consumedByAnotherLine() {
            UUID hold = UUID.randomUUID();
            StockItem item = stockWithHold(BIN, 10, 3, ReservationStatus.HELD, hold);
            service.consumeForPick(hold, UUID.randomUUID(), PICKER);
            saved.clear();

            assertThat(refusal(() -> service.consumeForPick(hold, UUID.randomUUID(), PICKER)))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE);
            assertThat(item.onHand()).isEqualTo(Quantity.of(7));
            assertThat(ledgerRows).hasSize(1);
            assertThat(saved).isEmpty();
        }

        @Test
        @DisplayName("a released or expired hold: RESERVATION_NOT_ACTIVE, nothing written, no event")
        void releasedOrExpiredHold() {
            for (ReservationStatus status : List.of(ReservationStatus.RELEASED, ReservationStatus.EXPIRED)) {
                UUID hold = UUID.randomUUID();
                StockItem item = stockWithHold(BIN, 10, 3, status, hold);

                assertThat(refusal(() -> service.consumeForPick(hold, UUID.randomUUID(), PICKER)))
                        .as(status.name()).isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE);
                assertThat(item.onHand()).isEqualTo(Quantity.of(10));
            }
            assertThat(ledgerRows).isEmpty();
            assertThat(saved).isEmpty();
            verify(publisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("an unknown hold: RESERVATION_NOT_FOUND")
        void unknownHold() {
            UUID hold = UUID.randomUUID();
            when(stockItems.findByReservationIdForUpdate(ReservationId.of(hold))).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.consumeForPick(hold, UUID.randomUUID(), PICKER)))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
            assertThat(ledgerRows).isEmpty();
        }

        @Test
        @DisplayName("the operations endpoint deducts the same way and names the order in the ledger")
        void byHand() {
            UUID hold = UUID.randomUUID();
            StockItem item = stockWithHold(BIN, 10, 3, ReservationStatus.HELD, hold);
            UUID order = item.findReservation(ReservationId.of(hold)).orElseThrow().orderId();

            service.consume(hold, PICKER);

            assertThat(item.onHand()).isEqualTo(Quantity.of(7));
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
        @DisplayName("an order's hold on goods a legacy print-shop row still records (IN_PRODUCTION) is refused too")
        void goodsInThePrintShopAreRefused() {
            UUID holdId = UUID.randomUUID();
            Reservation hold = new Reservation(ReservationId.of(holdId), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), Quantity.of(2), NOW.minusSeconds(3600), null, ReservationStatus.HELD, null, null);
            StockItem item = new StockItem(StockItemId.newId(), SOFA, new LocationId("HCM-PRD01"), "LOT-1",
                    Quantity.of(2), StockStatus.IN_PRODUCTION, List.of(hold), 0L,
                    NOW.minusSeconds(86_400));
            when(stockItems.findByReservationIdForUpdate(ReservationId.of(holdId))).thenReturn(Optional.of(item));

            assertThat(refusal(() -> service.consumeForPick(holdId, UUID.randomUUID(), PICKER)))
                    .isEqualTo(ErrorCode.PRODUCTION_HOLD_NOT_PICKABLE);
            assertThat(ledgerRows).isEmpty();
        }
    }

    @Nested
    @DisplayName("a short pick")
    class ShortPick {

        @Test
        @DisplayName("on a carton's row, the recount is of that carton, with its book quantity")
        void aCartonRowIsRecountedAsACarton() {
            UUID holdId = UUID.randomUUID();
            Reservation hold = new Reservation(ReservationId.of(holdId), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), Quantity.of(2), NOW.minusSeconds(3600), null, ReservationStatus.HELD, null, null);
            var carton = new com.stockflow.inventory.internal.domain.StockPackageId(UUID.randomUUID());
            StockItem item = new StockItem(StockItemId.newId(), SOFA, BIN, "LOT-1", Quantity.of(4), StockStatus.AVAILABLE, List.of(hold), 0L, NOW.minusSeconds(86_400), carton);
            when(stockItems.findIdByReservationId(ReservationId.of(holdId))).thenReturn(Optional.of(item.id()));
            when(stockItems.findById(item.id())).thenReturn(Optional.of(item));
            when(cycleCounts.findHolding(any())).thenReturn(Optional.empty());
            when(cycleCounts.bookQuantity(any())).thenReturn(12);
            when(cycleCounts.nextNumber(any())).thenReturn("CC-20261014-0001");
            when(cycleCounts.save(any())).thenAnswer(call -> call.getArgument(0));
            when(locations.warehouseOf(BIN)).thenReturn(Optional.of(UUID.randomUUID()));

            var recount = service.requestRecount(holdId, UUID.randomUUID(), 1);

            var slot = org.mockito.ArgumentCaptor.forClass(com.stockflow.inventory.internal.domain.CountSlot.class);
            verify(cycleCounts).findHolding(slot.capture());
            assertThat(slot.getValue().stockPackageId()).isEqualTo(carton);
            var saved = org.mockito.ArgumentCaptor.forClass(com.stockflow.inventory.internal.domain.CycleCount.class);
            verify(cycleCounts).save(saved.capture());
            assertThat(saved.getValue().lines().get(0).slot().stockPackageId()).isEqualTo(carton);
            assertThat(saved.getValue().lines().get(0).expectedQty()).isEqualTo(12);
            assertThat(recount.created()).isTrue();
        }
    }

    @Nested
    @DisplayName("putting a carton back (07 BR-05, section 4.5 step 14, team plan 4C-4)")
    class PuttingBack {

        private static final String LPN = "LPN-20261113-000001";

        /** The carton a pick took whole, and the one row of it the pick emptied. */
        private record Picked(StockItem row, StockPackage carton) { }

        private PutBackCommand command(UUID line, UUID hold, LocationId target) {
            return new PutBackCommand(line, hold, LPN, target.code(), PICKER);
        }

        /**
         * A carton of {@code count} units picked for an order: CONSUMED, and its row at 0 with the CONSUMED hold of the
         * whole carton, as a pick leaves them.
         */
        private Picked pickedCarton(LocationId bin, int count, UUID hold) {
            return pickedCarton(bin, count, count, ReservationStatus.CONSUMED, hold, StockStatus.AVAILABLE);
        }

        private Picked pickedCarton(LocationId bin, int received, int held, ReservationStatus holdStatus, UUID hold,
                                    StockStatus rowStatus) {
            Reservation reservation = new Reservation(ReservationId.of(hold), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), Quantity.of(held), NOW.minusSeconds(3600), null, holdStatus, null,
                    holdStatus == ReservationStatus.HELD ? null : NOW.minusSeconds(60));
            StockPackageId id = new StockPackageId(UUID.randomUUID());
            boolean consumed = holdStatus == ReservationStatus.CONSUMED;
            StockPackage carton = new StockPackage(id, LPN, UUID.randomUUID(), UUID.randomUUID(), received, received,
                    bin, consumed ? StockPackageStatus.CONSUMED : StockPackageStatus.IN_STOCK, !consumed, null,
                    consumed ? NOW.minusSeconds(60) : null, 0L);
            StockItem row = new StockItem(StockItemId.newId(), SOFA, bin, "LOT-1",
                    consumed ? Quantity.ZERO : Quantity.of(received), rowStatus, List.of(reservation), 0L,
                    NOW.minusSeconds(86_400), null, id);
            when(stockItems.findIdByReservationId(ReservationId.of(hold))).thenReturn(Optional.of(row.id()));
            when(stockItems.findById(row.id())).thenReturn(Optional.of(row));
            when(stockItems.findByIdForUpdate(row.id())).thenReturn(Optional.of(row));
            when(stockPackages.findAllByIdForUpdate(any())).thenReturn(List.of(carton));
            when(stockPackages.findById(id)).thenReturn(Optional.of(carton));
            when(stockPackages.save(any())).thenAnswer(call -> call.getArgument(0));
            return new Picked(row, carton);
        }

        @Test
        @DisplayName("back to the bin it left: CONSUMED to IN_STOCK sealed, its row refilled as AVAILABLE, one PUT_BACK row naming the carton")
        void backToTheSameBin() {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, hold);

            service.putBack(command(line, hold, BIN));

            assertThat(picked.carton().status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(picked.carton().unopened()).as("sealed again").isTrue();
            assertThat(picked.carton().closedAt()).isNull();
            assertThat(picked.carton().location()).isEqualTo(BIN);
            assertThat(picked.carton().lpn()).as("it keeps its LPN").isEqualTo(LPN);
            assertThat(picked.row().onHand()).isEqualTo(Quantity.of(3));
            assertThat(picked.row().status()).isEqualTo(StockStatus.AVAILABLE);
            assertThat(picked.row().location()).isEqualTo(BIN);
            assertThat(merged).as("a raise of an existing layer is a same-receipt merge").containsExactly(picked.row());
            assertThat(saved).as("no new row: the carton's own took its units back").isEmpty();
            verify(stockPackages).save(picked.carton());
            assertThat(ledgerRows).singleElement().satisfies(row -> {
                assertThat(row.type()).isEqualTo(StockMovement.MovementType.PUT_BACK);
                assertThat(row.referenceType()).isEqualTo(StockMovement.ReferenceType.PICK_LINE);
                assertThat(row.referenceId()).isEqualTo(line);
                assertThat(row.from()).isNull();
                assertThat(row.to()).isEqualTo(BIN);
                assertThat(row.quantity()).isEqualTo(3);
                assertThat(row.toStatus()).isEqualTo(StockStatus.AVAILABLE);
                assertThat(row.lotNumber()).isEqualTo("LOT-1");
                assertThat(row.toStockPackage()).as("the ledger names the carton").isEqualTo(picked.carton().id());
                assertThat(row.fromStockPackage()).isNull();
            });
            verify(policies).lock("SOFA-3S-GREY");
        }

        @Test
        @DisplayName("onto another bin: the carton and its row follow it there whole; no row is created or split")
        void ontoAnotherBin() {
            UUID hold = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, hold);

            service.putBack(command(UUID.randomUUID(), hold, OTHER_BIN));

            assertThat(picked.carton().location()).isEqualTo(OTHER_BIN);
            assertThat(picked.carton().status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(picked.row().location()).as("the row follows its carton").isEqualTo(OTHER_BIN);
            assertThat(picked.row().onHand()).isEqualTo(Quantity.of(3));
            assertThat(picked.row().sku()).isEqualTo(SOFA);
            assertThat(picked.row().lotNumber()).isEqualTo("LOT-1");
            assertThat(saved).isEmpty();
            assertThat(ledgerRows).singleElement().satisfies(row -> {
                assertThat(row.to()).isEqualTo(OTHER_BIN);
                assertThat(row.toStockPackage()).isEqualTo(picked.carton().id());
            });
        }

        private final LocationId quarantine = new LocationId("HCM-QC01");

        @Test
        @DisplayName("a damaged carton (removed at the check station) goes whole into a QUARANTINE area: IN_STOCK there, its row "
                + "QUARANTINE with its units, one PUT_BACK row whose status is QUARANTINE - never AVAILABLE (4C-6)")
        void damagedCartonToQuarantine() {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, hold);
            when(locations.isQuarantineArea(quarantine)).thenReturn(true);
            UUID warehouse = UUID.randomUUID();
            when(locations.warehouseOf(BIN)).thenReturn(Optional.of(warehouse));
            when(locations.warehouseOf(quarantine)).thenReturn(Optional.of(warehouse));

            service.putBackToQuarantine(command(line, hold, quarantine));

            assertThat(picked.carton().status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(picked.carton().location()).isEqualTo(quarantine);
            assertThat(picked.carton().lpn()).isEqualTo(LPN);
            assertThat(picked.row().status()).isEqualTo(StockStatus.QUARANTINE);
            assertThat(picked.row().location()).as("the row follows its carton").isEqualTo(quarantine);
            assertThat(picked.row().onHand()).isEqualTo(Quantity.of(3));
            assertThat(ledgerRows).singleElement().satisfies(row -> {
                assertThat(row.type()).isEqualTo(StockMovement.MovementType.PUT_BACK);
                assertThat(row.referenceId()).isEqualTo(line);
                assertThat(row.to()).isEqualTo(quarantine);
                assertThat(row.toStatus()).isEqualTo(StockStatus.QUARANTINE);
                assertThat(row.toStockPackage()).isEqualTo(picked.carton().id());
            });
            var order = org.mockito.Mockito.inOrder(policies, stockPackages, stockItems);
            order.verify(policies).lock("SOFA-3S-GREY");
            order.verify(stockPackages).findAllByIdForUpdate(any());
            order.verify(stockItems).findByIdForUpdate(picked.row().id());
        }

        @Test
        @DisplayName("a bin is not a quarantine area: LOCATION_AREA_MISMATCH, and the carton stays picked")
        void damagedCartonNeedsAQuarantineArea() {
            UUID hold = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, hold);
            when(locations.isQuarantineArea(OTHER_BIN)).thenReturn(false);

            assertThat(refusal(() -> service.putBackToQuarantine(command(UUID.randomUUID(), hold, OTHER_BIN))))
                    .isEqualTo(ErrorCode.LOCATION_AREA_MISMATCH);

            assertThat(picked.carton().status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(picked.row().onHand()).isEqualTo(Quantity.ZERO);
            assertThat(ledgerRows).isEmpty();
            verify(stockPackages, never()).save(any());
        }

        @Test
        @DisplayName("a quarantine area of another warehouse is MOVE_ACROSS_WAREHOUSES: stock never changes warehouse")
        void damagedCartonStaysInItsWarehouse() {
            UUID hold = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, hold);
            when(locations.isQuarantineArea(quarantine)).thenReturn(true);
            when(locations.warehouseOf(BIN)).thenReturn(Optional.of(UUID.randomUUID()));
            when(locations.warehouseOf(quarantine)).thenReturn(Optional.of(UUID.randomUUID()));

            assertThat(refusal(() -> service.putBackToQuarantine(command(UUID.randomUUID(), hold, quarantine))))
                    .isEqualTo(ErrorCode.MOVE_ACROSS_WAREHOUSES);

            assertThat(picked.carton().status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(ledgerRows).isEmpty();
        }

        @Test
        @DisplayName("a quarantine area whose warehouse cannot be resolved is LOCATION_NOT_FOUND; two unknown places never pass "
                + "as 'the same warehouse'")
        void unknownTargetWarehouse() {
            UUID hold = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, hold);
            when(locations.isQuarantineArea(quarantine)).thenReturn(true);
            when(locations.warehouseOf(BIN)).thenReturn(Optional.empty());
            when(locations.warehouseOf(quarantine)).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.putBackToQuarantine(command(UUID.randomUUID(), hold, quarantine))))
                    .isEqualTo(ErrorCode.LOCATION_NOT_FOUND);

            assertThat(picked.carton().status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(ledgerRows).isEmpty();
        }

        @Test
        @DisplayName("a carton standing at a location with no warehouse is a data fault: IllegalStateException, nothing changes")
        void cartonAtAnUnknownLocation() {
            UUID hold = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, hold);
            when(locations.isQuarantineArea(quarantine)).thenReturn(true);
            when(locations.warehouseOf(quarantine)).thenReturn(Optional.of(UUID.randomUUID()));
            when(locations.warehouseOf(BIN)).thenReturn(Optional.empty());

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.putBackToQuarantine(
                    command(UUID.randomUUID(), hold, quarantine))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(LPN);

            assertThat(picked.carton().status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(ledgerRows).isEmpty();
        }

        @Test
        @DisplayName("an unknown or unusable quarantine area is refused before the area rule; the same line again changes nothing")
        void damagedCartonReplayAndUsability() {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, hold);
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
            assertThat(picked.row().status()).isEqualTo(StockStatus.QUARANTINE);
            verify(stockPackages, org.mockito.Mockito.times(1)).save(any());
        }

        @Test
        @DisplayName("the SKU is locked, then the carton, then its row - in that order")
        void lockOrder() {
            UUID hold = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, hold);

            service.putBack(command(UUID.randomUUID(), hold, BIN));

            var order = org.mockito.Mockito.inOrder(policies, stockPackages, stockItems);
            order.verify(policies).lock("SOFA-3S-GREY");
            order.verify(stockPackages).findAllByIdForUpdate(any());
            order.verify(stockItems).findByIdForUpdate(picked.row().id());
            // The carton is saved before the row, so it stands at its new bin when the ledger line names it there
            // (check_stock_movement_package): a put-back onto another bin failed 409 when the order was the reverse.
            order.verify(stockPackages).save(picked.carton());
            order.verify(stockItems).saveMerged(picked.row());
        }

        @Test
        @DisplayName("the same line again changes nothing: one ledger row, the carton is saved once")
        void idempotentPerLine() {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, hold);
            service.putBack(command(line, hold, BIN));
            merged.clear();

            service.putBack(command(line, hold, OTHER_BIN));

            assertThat(picked.row().onHand()).isEqualTo(Quantity.of(3));
            assertThat(picked.carton().location()).as("the replay is not a second move").isEqualTo(BIN);
            assertThat(ledgerRows).hasSize(1);
            assertThat(merged).isEmpty();
            assertThat(saved).isEmpty();
            verify(stockPackages, org.mockito.Mockito.times(1)).save(any());
        }

        @Test
        @DisplayName("an unknown or unusable bin is refused before any stock changes")
        void unusableBin() {
            UUID hold = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, hold);
            when(locations.stateOf(OTHER_BIN)).thenReturn(LocationDirectory.LocationState.UNUSABLE);
            LocationId nowhere = new LocationId("HCM-ZZ9-9-Z");
            when(locations.stateOf(nowhere)).thenReturn(LocationDirectory.LocationState.UNKNOWN);

            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, OTHER_BIN))))
                    .isEqualTo(ErrorCode.LOCATION_NOT_USABLE);
            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, nowhere))))
                    .isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
            assertThat(picked.carton().status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(picked.row().onHand()).isEqualTo(Quantity.ZERO);
            assertThat(ledgerRows).isEmpty();
            assertThat(saved).isEmpty();
            assertThat(merged).isEmpty();
            verify(stockPackages, never()).save(any());
        }

        @Test
        @DisplayName("a pick of loose stock (no carton) is STOCK_PACKAGE_TAKEN_WHOLE: loose stock is not put back")
        void looseStockIsRefused() {
            UUID hold = UUID.randomUUID();
            StockItem item = stockWithHold(BIN, 0, 3, ReservationStatus.CONSUMED, hold);
            when(stockItems.findIdByReservationId(ReservationId.of(hold))).thenReturn(Optional.of(item.id()));
            when(stockItems.findById(item.id())).thenReturn(Optional.of(item));

            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, BIN))))
                    .isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(item.onHand()).isEqualTo(Quantity.ZERO);
            assertThat(ledgerRows).isEmpty();
            assertThat(saved).isEmpty();
            assertThat(merged).isEmpty();
        }

        @Test
        @DisplayName("a hold that is not the carton's whole count is STOCK_PACKAGE_TAKEN_WHOLE")
        void partOfTheCartonIsRefused() {
            UUID hold = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 4, 3, ReservationStatus.CONSUMED, hold, StockStatus.AVAILABLE);

            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, BIN))))
                    .isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(picked.carton().status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(ledgerRows).isEmpty();
            verify(stockPackages, never()).save(any());
        }

        @Test
        @DisplayName("a hold the pick did not consume is not a carton that left: STOCK_PACKAGE_TAKEN_WHOLE")
        void notConsumed() {
            UUID hold = UUID.randomUUID();
            pickedCarton(BIN, 3, 3, ReservationStatus.HELD, hold, StockStatus.AVAILABLE);

            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, BIN))))
                    .isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(ledgerRows).isEmpty();
        }

        @Test
        @DisplayName("a row that is not AVAILABLE stock does not take the carton back: STOCK_STATUS_MISMATCH")
        void rowNotAvailable() {
            UUID hold = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, 3, ReservationStatus.CONSUMED, hold, StockStatus.QUARANTINE);

            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, BIN))))
                    .isEqualTo(ErrorCode.STOCK_STATUS_MISMATCH);
            assertThat(picked.row().onHand()).isEqualTo(Quantity.ZERO);
            assertThat(ledgerRows).isEmpty();
            assertThat(merged).isEmpty();
            verify(stockPackages, never()).save(any());
        }

        @Test
        @DisplayName("a command naming another LPN than the hold's carton is a caller's bug: IllegalStateException")
        void anotherLpnIsACallersBug() {
            UUID hold = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, hold);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.putBack(new PutBackCommand(
                    UUID.randomUUID(), hold, "LPN-SOMEONE-ELSE", BIN.code(), PICKER)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(picked.carton().status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(ledgerRows).isEmpty();
        }

        @Test
        @DisplayName("the LPN is compared in any case")
        void lpnInAnyCase() {
            UUID hold = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, hold);

            service.putBack(new PutBackCommand(UUID.randomUUID(), hold, " " + LPN.toLowerCase() + " ", BIN.code(),
                    PICKER));

            assertThat(picked.carton().status()).isEqualTo(StockPackageStatus.IN_STOCK);
        }

        @Test
        @DisplayName("an unknown hold is RESERVATION_NOT_FOUND")
        void unknownHold() {
            UUID hold = UUID.randomUUID();
            when(stockItems.findIdByReservationId(ReservationId.of(hold))).thenReturn(Optional.empty());

            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, BIN))))
                    .isEqualTo(ErrorCode.RESERVATION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("staging a carton, deducting it from the staging area, putting it back (team plan 4C-5)")
    class Staging {

        private static final String LPN = "LPN-20261113-000001";
        private final LocationId staging = new LocationId("HCM-STG01");

        private record Held(StockItem row, StockPackage carton, UUID hold) { }

        @BeforeEach
        void stagingIsAnArea() {
            when(locations.isStagingArea(staging)).thenReturn(true);
            when(locations.stateOf(staging)).thenReturn(LocationDirectory.LocationState.USABLE);
        }

        private StageCommand stageCommand(UUID line, UUID hold) {
            return new StageCommand(line, hold, LPN, staging.code(), PICKER);
        }

        /** A carton of {@code count} units sealed in {@code bin}, its one row all held for an order. */
        private Held heldCarton(LocationId bin, int count, int held, ReservationStatus status, StockStatus rowStatus) {
            UUID hold = UUID.randomUUID();
            Reservation reservation = new Reservation(ReservationId.of(hold), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), Quantity.of(held), NOW.minusSeconds(3600), null, status, null,
                    status == ReservationStatus.HELD ? null : NOW.minusSeconds(60));
            StockPackageId id = new StockPackageId(UUID.randomUUID());
            StockPackage carton = new StockPackage(id, LPN, UUID.randomUUID(), UUID.randomUUID(), count, count, bin,
                    StockPackageStatus.IN_STOCK, true, null, null, 0L);
            StockItem row = new StockItem(StockItemId.newId(), SOFA, bin, "LOT-1",
                    Quantity.of(count), rowStatus, List.of(reservation), 0L, NOW.minusSeconds(86_400), null, id);
            when(stockItems.findIdByReservationId(ReservationId.of(hold))).thenReturn(Optional.of(row.id()));
            when(stockItems.findById(row.id())).thenReturn(Optional.of(row));
            when(stockItems.findByIdForUpdate(row.id())).thenReturn(Optional.of(row));
            when(stockItems.findByReservationIdForUpdate(ReservationId.of(hold))).thenReturn(Optional.of(row));
            when(stockItems.findIdsInStockPackage(id)).thenReturn(List.of(row.id()));
            when(stockPackages.findAllByIdForUpdate(any())).thenReturn(List.of(carton));
            when(stockPackages.save(any())).thenAnswer(call -> call.getArgument(0));
            return new Held(row, carton, hold);
        }

        private Held heldCarton(LocationId bin) {
            return heldCarton(bin, 6, 6, ReservationStatus.HELD, StockStatus.AVAILABLE);
        }

        @Test
        @DisplayName("stage: the carton and its row move whole to the staging area, the hold stays HELD, one MOVE row names the line")
        void stagesWhole() {
            Held held = heldCarton(BIN);
            UUID line = UUID.randomUUID();

            StagedPick staged = service.stage(stageCommand(line, held.hold()));

            assertThat(staged).isEqualTo(new StagedPick(held.hold(), LPN, "SOFA-3S-GREY", 6, BIN.code(),
                    staging.code(), false));
            assertThat(held.carton().location()).isEqualTo(staging);
            assertThat(held.carton().status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(held.row().location()).isEqualTo(staging);
            assertThat(held.row().onHand()).as("nothing is deducted").isEqualTo(Quantity.of(6));
            assertThat(held.row().findReservation(ReservationId.of(held.hold())).orElseThrow().status())
                    .isEqualTo(ReservationStatus.HELD);
            assertThat(ledgerRows).singleElement().satisfies(row -> {
                assertThat(row.type()).isEqualTo(StockMovement.MovementType.MOVE);
                assertThat(row.referenceType()).isEqualTo(StockMovement.ReferenceType.PICK_LINE);
                assertThat(row.referenceId()).isEqualTo(line);
                assertThat(row.from()).isEqualTo(BIN);
                assertThat(row.to()).isEqualTo(staging);
                assertThat(row.quantity()).isEqualTo(6);
                assertThat(row.fromStockPackage()).isEqualTo(held.carton().id());
                assertThat(row.toStockPackage()).isEqualTo(held.carton().id());
            });
            verify(publisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("stage: SKU lock, then the carton, then its row - in that order")
        void stageLockOrder() {
            Held held = heldCarton(BIN);

            service.stage(stageCommand(UUID.randomUUID(), held.hold()));

            var order = org.mockito.Mockito.inOrder(policies, stockPackages, stockItems);
            order.verify(policies).lock("SOFA-3S-GREY");
            order.verify(stockPackages).findAllByIdForUpdate(any());
            order.verify(stockItems).findByIdForUpdate(held.row().id());
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
            assertThat(again.stagingLocationCode()).isEqualTo(staging.code());
            assertThat(again.fromLocationCode()).isEqualTo(BIN.code());
            assertThat(ledgerRows).hasSize(1);
            assertThat(saved).isEmpty();
            verify(stockPackages, org.mockito.Mockito.times(1)).save(any());
        }

        @Test
        @DisplayName("stage: loose stock is STOCK_PACKAGE_TAKEN_WHOLE, nothing moves")
        void stageLooseRow() {
            UUID hold = UUID.randomUUID();
            StockItem item = stockWithHold(BIN, 6, 6, ReservationStatus.HELD, hold);
            when(stockItems.findIdByReservationId(ReservationId.of(hold))).thenReturn(Optional.of(item.id()));
            when(stockItems.findById(item.id())).thenReturn(Optional.of(item));

            assertThat(refusal(() -> service.stage(stageCommand(UUID.randomUUID(), hold))))
                    .isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(item.location()).isEqualTo(BIN);
            assertThat(ledgerRows).isEmpty();
        }

        @Test
        @DisplayName("stage: a carton with two rows, or a hold of part of it, is STOCK_PACKAGE_TAKEN_WHOLE")
        void stageNotTheWholeCarton() {
            Held twoRows = heldCarton(BIN);
            when(stockItems.findIdsInStockPackage(twoRows.carton().id()))
                    .thenReturn(List.of(twoRows.row().id(), StockItemId.newId()));
            assertThat(refusal(() -> service.stage(stageCommand(UUID.randomUUID(), twoRows.hold()))))
                    .as("two rows").isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(twoRows.carton().location()).isEqualTo(BIN);

            Held partHeld = heldCarton(BIN, 6, 4, ReservationStatus.HELD, StockStatus.AVAILABLE);
            assertThat(refusal(() -> service.stage(stageCommand(UUID.randomUUID(), partHeld.hold()))))
                    .as("part held").isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(partHeld.carton().location()).isEqualTo(BIN);
            assertThat(ledgerRows).isEmpty();
            verify(stockPackages, never()).save(any());
        }

        @Test
        @DisplayName("stage: a hold that is not HELD is RESERVATION_NOT_ACTIVE; a row that is not AVAILABLE is STOCK_STATUS_MISMATCH")
        void stageHoldAndRowState() {
            for (ReservationStatus status : List.of(ReservationStatus.CONSUMED, ReservationStatus.RELEASED)) {
                Held notHeld = heldCarton(BIN, 6, 6, status, StockStatus.AVAILABLE);
                assertThat(refusal(() -> service.stage(stageCommand(UUID.randomUUID(), notHeld.hold()))))
                        .as(status.name()).isEqualTo(ErrorCode.RESERVATION_NOT_ACTIVE);
            }
            Held quarantined = heldCarton(BIN, 6, 6, ReservationStatus.HELD, StockStatus.QUARANTINE);
            assertThat(refusal(() -> service.stage(stageCommand(UUID.randomUUID(), quarantined.hold()))))
                    .isEqualTo(ErrorCode.STOCK_STATUS_MISMATCH);
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
        @DisplayName("consumeStaged: deducts from the staging area - hold CONSUMED, on_hand 0, carton CONSUMED, one PICK row from staging")
        void consumeStagedDeducts() {
            UUID line = UUID.randomUUID();
            Held held = staged(line);

            ConsumedPick consumed = service.consumeStaged(held.hold(), line, PICKER);

            assertThat(consumed).isEqualTo(new ConsumedPick(held.hold(), "SOFA-3S-GREY", staging.code(), "LOT-1", 6,
                    false));
            assertThat(held.row().onHand()).isEqualTo(Quantity.ZERO);
            assertThat(held.row().findReservation(ReservationId.of(held.hold())).orElseThrow().status())
                    .isEqualTo(ReservationStatus.CONSUMED);
            assertThat(held.carton().status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(ledgerRows).hasSize(2);
            assertThat(ledgerRows.get(1)).satisfies(row -> {
                assertThat(row.type()).isEqualTo(StockMovement.MovementType.PICK);
                assertThat(row.referenceType()).isEqualTo(StockMovement.ReferenceType.PICK_LINE);
                assertThat(row.referenceId()).isEqualTo(line);
                assertThat(row.from()).isEqualTo(staging);
                assertThat(row.to()).isNull();
                assertThat(row.quantity()).isEqualTo(6);
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
            assertThat(held.row().onHand()).isEqualTo(Quantity.of(6));
            assertThat(ledgerRows).isEmpty();
        }

        /** The order was cancelled: its hold is released, the carton is still in the staging area. */
        private void cancelled(Held held) {
            held.row().releaseReservation(ReservationId.of(held.hold()), ReleaseReason.ORDER_CANCELLED, NOW);
        }

        @Test
        @DisplayName("unstage: back onto a bin - carton and row move whole, one second MOVE row")
        void unstageMovesBack() {
            UUID line = UUID.randomUUID();
            Held held = staged(line);
            cancelled(held);

            service.unstage(new PutBackCommand(line, held.hold(), LPN, OTHER_BIN.code(), PICKER));

            assertThat(held.carton().location()).isEqualTo(OTHER_BIN);
            assertThat(held.carton().status()).isEqualTo(StockPackageStatus.IN_STOCK);
            assertThat(held.row().location()).isEqualTo(OTHER_BIN);
            assertThat(held.row().onHand()).isEqualTo(Quantity.of(6));
            assertThat(ledgerRows).hasSize(2);
            assertThat(ledgerRows.get(1)).satisfies(row -> {
                assertThat(row.type()).isEqualTo(StockMovement.MovementType.MOVE);
                assertThat(row.referenceId()).isEqualTo(line);
                assertThat(row.from()).isEqualTo(staging);
                assertThat(row.to()).isEqualTo(OTHER_BIN);
                assertThat(row.toStockPackage()).isEqualTo(held.carton().id());
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
    }

    @Nested
    @DisplayName("the whole task moves to dispatch in one call (review M1)")
    class BulkMoveToDispatch {

        private final LocationId staging = new LocationId("HCM-STG01");
        private final java.util.Map<StockPackageId, StockPackage> cartonsById = new java.util.HashMap<>();

        private record Line(StockItem row, StockPackage carton, UUID hold, UUID line, StagedLine staged) { }

        @BeforeEach
        void stubs() {
            when(locations.isStagingArea(staging)).thenReturn(true);
            when(locations.stateOf(staging)).thenReturn(LocationDirectory.LocationState.USABLE);
            when(stockPackages.findAllByIdForUpdate(any())).thenAnswer(call ->
                    ((java.util.Collection<StockPackageId>) call.getArgument(0)).stream()
                            .map(cartonsById::get).filter(java.util.Objects::nonNull).toList());
            when(stockPackages.save(any())).thenAnswer(call -> call.getArgument(0));
        }

        /** A sealed carton of {@code sku} held whole for an order and staged by its own pick line. */
        private Line stagedLine(String sku, String lpn, LocationId bin) {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            Reservation reservation = new Reservation(ReservationId.of(hold), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), Quantity.of(2), NOW.minusSeconds(3600), null, ReservationStatus.HELD, null, null);
            StockPackageId id = new StockPackageId(UUID.randomUUID());
            StockPackage carton = new StockPackage(id, lpn, UUID.randomUUID(), UUID.randomUUID(), 2, 2, bin,
                    StockPackageStatus.IN_STOCK, true, null, null, 0L);
            cartonsById.put(id, carton);
            StockItem row = new StockItem(StockItemId.newId(), new Sku(sku), bin, "LOT-1",
                    Quantity.of(2), StockStatus.AVAILABLE, List.of(reservation), 0L, NOW.minusSeconds(86_400), null, id);
            when(stockItems.findIdByReservationId(ReservationId.of(hold))).thenReturn(Optional.of(row.id()));
            when(stockItems.findById(row.id())).thenReturn(Optional.of(row));
            when(stockItems.findByIdForUpdate(row.id())).thenReturn(Optional.of(row));
            when(stockItems.findByReservationIdForUpdate(ReservationId.of(hold))).thenReturn(Optional.of(row));
            when(stockItems.findIdsInStockPackage(id)).thenReturn(List.of(row.id()));
            service.stage(new StageCommand(line, hold, lpn, staging.code(), PICKER));
            saved.clear();
            return new Line(row, carton, hold, line, new StagedLine(hold, line));
        }

        @Test
        @DisplayName("lines of two SKUs given in reverse order: SKU locks sorted, cartons locked once, rows by id, cartons settled once")
        void locksInOneOrderForTheWholeTask() {
            Line b = stagedLine("SKU-B", "LPN-B", BIN);
            Line a = stagedLine("SKU-A", "LPN-A", OTHER_BIN);
            org.mockito.Mockito.clearInvocations(policies, stockPackages, stockItems);

            var consumed = service.consumeStaged(List.of(b.staged(), a.staged()), PICKER);

            assertThat(consumed).extracting(ConsumedPick::sku).containsExactly("SKU-B", "SKU-A");
            assertThat(consumed).allMatch(c -> !c.alreadyConsumed());
            var order = org.mockito.Mockito.inOrder(policies, stockPackages, stockItems);
            order.verify(policies).lock("SKU-A");
            order.verify(policies).lock("SKU-B");
            order.verify(stockPackages).findAllByIdForUpdate(any());
            // each row is locked, through its hold, in row id order - not in the order the lines were given
            List<UUID> holdsByRowId = java.util.stream.Stream.of(a, b)
                    .sorted(java.util.Comparator.comparing(l -> l.row().id().value().toString()))
                    .map(Line::hold).toList();
            for (UUID hold : holdsByRowId) {
                order.verify(stockItems).findByReservationIdForUpdate(ReservationId.of(hold));
            }
            verify(stockPackages, org.mockito.Mockito.times(1)).findAllByIdForUpdate(any());
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
        @DisplayName("a carton a count found LOST, or one that is elsewhere, is CONFLICT and nothing of the task is deducted")
        void aCartonNotWhereItWasStagedRefusesTheWholeTask() {
            Line good = stagedLine("SKU-A", "LPN-A", OTHER_BIN);
            Line lost = stagedLine("SKU-B", "LPN-B", BIN);
            lost.carton().lose(NOW);
            org.mockito.Mockito.clearInvocations(stockPackages);

            assertThat(refusal(() -> service.consumeStaged(List.of(good.staged(), lost.staged()), PICKER)))
                    .isEqualTo(ErrorCode.CONFLICT);
            // the refusal rolls the transaction back; no carton was settled (settling comes after every row)
            verify(stockPackages, never()).save(any());
            assertThat(good.carton().status()).isEqualTo(StockPackageStatus.IN_STOCK);

            ledgerRows.clear();   // the first refusal rolled back in production; this mock ledger does not
            Line moved = stagedLine("SKU-C", "LPN-C", BIN);
            moved.carton().moveTo(OTHER_BIN);
            assertThat(refusal(() -> service.consumeStaged(List.of(moved.staged()), PICKER)))
                    .isEqualTo(ErrorCode.CONFLICT);
            assertThat(moved.row().onHand()).isEqualTo(Quantity.of(2));
            assertThat(ledgerRows.stream().filter(r -> r.type() == StockMovement.MovementType.PICK)).isEmpty();
        }
    }
}
