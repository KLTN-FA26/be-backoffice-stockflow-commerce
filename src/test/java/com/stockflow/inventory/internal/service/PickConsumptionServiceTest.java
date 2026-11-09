package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.StockDeducted;
import com.stockflow.inventory.consumption.ConsumedPick;
import com.stockflow.inventory.consumption.PutBackCommand;
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
import java.time.LocalDate;
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
        public List<com.stockflow.inventory.internal.domain.StockPackageId> cartonsMovedBy(
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
    private final com.stockflow.inventory.internal.domain.StockDamageReportRepository damageReports =
            mock(com.stockflow.inventory.internal.domain.StockDamageReportRepository.class);
    private final PickConsumptionService service = new PickConsumptionService(stockItems, ledger,
            new InventoryEventPublisher(publisher), locations, policies,
            new StockPackageContents(stockPackages, stockItems), Clock.fixed(NOW, ZoneOffset.UTC),
            cycleCounts, damageReports, stockPackages);
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
        StockItem item = new StockItem(StockItemId.newId(), SOFA, location, "LOT-1", LocalDate.of(2030, 1, 1),
                Quantity.of(onHand), StockStatus.AVAILABLE, List.of(hold), 0L, NOW.minusSeconds(86_400), null);
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
                    LocalDate.of(2030, 1, 1), Quantity.of(2), StockStatus.IN_PRODUCTION, List.of(hold), 0L,
                    NOW.minusSeconds(86_400), null);
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
            StockItem item = new StockItem(StockItemId.newId(), SOFA, BIN, "LOT-1", LocalDate.of(2030, 1, 1),
                    Quantity.of(4), StockStatus.AVAILABLE, List.of(hold), 0L, NOW.minusSeconds(86_400), null, carton);
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
            StockItem row = new StockItem(StockItemId.newId(), SOFA, bin, "LOT-1", LocalDate.of(2030, 1, 1),
                    consumed ? Quantity.ZERO : Quantity.of(received), rowStatus, List.of(reservation), 0L,
                    NOW.minusSeconds(86_400), null, null, id);
            when(stockItems.findIdByReservationId(ReservationId.of(hold))).thenReturn(Optional.of(row.id()));
            when(stockItems.findById(row.id())).thenReturn(Optional.of(row));
            when(stockItems.findByIdForUpdate(row.id())).thenReturn(Optional.of(row));
            when(stockPackages.findAllByIdForUpdate(any())).thenReturn(List.of(carton));
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
        @DisplayName("units found damaged at packing make it no carton: STOCK_PACKAGE_TAKEN_WHOLE, nothing written")
        void damagedAtPackingIsRefused() {
            UUID hold = UUID.randomUUID();
            Picked picked = pickedCarton(BIN, 3, hold);
            when(damageReports.damagedAfterPick(hold)).thenReturn(1);

            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, BIN))))
                    .isEqualTo(ErrorCode.STOCK_PACKAGE_TAKEN_WHOLE);
            assertThat(picked.carton().status()).isEqualTo(StockPackageStatus.CONSUMED);
            assertThat(picked.row().onHand()).isEqualTo(Quantity.ZERO);
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
}
