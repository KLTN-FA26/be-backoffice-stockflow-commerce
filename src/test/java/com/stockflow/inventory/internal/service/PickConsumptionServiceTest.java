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
        public Optional<StockMovement> findByReference(StockMovement.MovementType type,
                                                       StockMovement.ReferenceType referenceType, UUID referenceId) {
            return ledgerRows.stream().filter(row -> row.type() == type && row.referenceType() == referenceType
                    && row.referenceId().equals(referenceId)).findFirst();
        }

        @Override
        public boolean handedOverFromProduction(com.stockflow.inventory.internal.domain.ReservationId hold,
                                                LocationId location) {
            return ledgerRows.stream().anyMatch(row -> row.type() == StockMovement.MovementType.MOVE
                    && row.referenceType() == StockMovement.ReferenceType.PRODUCTION_ORDER);
        }
    };
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final LocationDirectory locations = mock(LocationDirectory.class);
    private final InventoryPolicyRepository policies = mock(InventoryPolicyRepository.class);
    private final PickConsumptionService service = new PickConsumptionService(stockItems, ledger,
            new InventoryEventPublisher(publisher), locations, policies, Clock.fixed(NOW, ZoneOffset.UTC));
    private final List<StockItem> saved = new ArrayList<>();

    @BeforeEach
    void storeSaves() {
        when(stockItems.save(any())).thenAnswer(call -> {
            StockItem item = call.getArgument(0);
            saved.add(item);
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
    @DisplayName("production's stock is never picked")
    class ProductionStock {

        @Test
        @DisplayName("a production order's hold is refused, by a pick line and by hand, and nothing moves")
        void aProductionHoldIsRefused() {
            UUID holdId = UUID.randomUUID();
            Reservation hold = new Reservation(ReservationId.of(holdId), null, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), Quantity.of(2), NOW.minusSeconds(3600), null, ReservationStatus.HELD, null, null);
            StockItem item = new StockItem(StockItemId.newId(), SOFA, BIN, "LOT-1", LocalDate.of(2030, 1, 1),
                    Quantity.of(10), StockStatus.AVAILABLE, List.of(hold), 0L, NOW.minusSeconds(86_400), null);
            when(stockItems.findByReservationIdForUpdate(ReservationId.of(holdId))).thenReturn(Optional.of(item));

            assertThat(refusal(() -> service.consumeForPick(holdId, UUID.randomUUID(), PICKER)))
                    .isEqualTo(ErrorCode.PRODUCTION_HOLD_NOT_PICKABLE);
            assertThat(refusal(() -> service.consume(holdId, PICKER))).isEqualTo(ErrorCode.PRODUCTION_HOLD_NOT_PICKABLE);
            assertThat(ledgerRows).isEmpty();
            assertThat(saved).isEmpty();
        }

        @Test
        @DisplayName("an order's hold on goods in the print shop is refused too")
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
    @DisplayName("putting picked goods back")
    class PuttingBack {

        private PutBackCommand command(UUID line, UUID hold, LocationId target, int quantity) {
            return new PutBackCommand(line, hold, "SOFA-3S-GREY", "LOT-1", target.code(), quantity, PICKER);
        }

        @Test
        @DisplayName("back to the bin they left: merged into the same stock row as AVAILABLE, one PUT_BACK row from nowhere")
        void backToTheSameBin() {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            StockItem item = stockWithHold(BIN, 7, 3, ReservationStatus.CONSUMED, hold);

            service.putBack(command(line, hold, BIN, 3));

            assertThat(item.onHand()).isEqualTo(Quantity.of(10));
            assertThat(saved).containsExactly(item);
            assertThat(ledgerRows).singleElement().satisfies(row -> {
                assertThat(row.type()).isEqualTo(StockMovement.MovementType.PUT_BACK);
                assertThat(row.referenceType()).isEqualTo(StockMovement.ReferenceType.PICK_LINE);
                assertThat(row.referenceId()).isEqualTo(line);
                assertThat(row.from()).isNull();
                assertThat(row.to()).isEqualTo(BIN);
                assertThat(row.quantity()).isEqualTo(3);
                assertThat(row.toStatus()).isEqualTo(StockStatus.AVAILABLE);
                assertThat(row.lotNumber()).isEqualTo("LOT-1");
            });
            verify(policies).lock("SOFA-3S-GREY");
        }

        @Test
        @DisplayName("onto another bin with no such layer: a new AVAILABLE row with the same SKU, lot, expiry and receipt")
        void createsTheLayerElsewhere() {
            UUID hold = UUID.randomUUID();
            StockItem source = stockWithHold(BIN, 0, 3, ReservationStatus.CONSUMED, hold);
            when(stockItems.findBySkuAndLocation(SOFA, OTHER_BIN)).thenReturn(List.of());

            service.putBack(command(UUID.randomUUID(), hold, OTHER_BIN, 3));

            assertThat(saved).singleElement().satisfies(created -> {
                assertThat(created.id()).isNotEqualTo(source.id());
                assertThat(created.location()).isEqualTo(OTHER_BIN);
                assertThat(created.sku()).isEqualTo(SOFA);
                assertThat(created.lotNumber()).isEqualTo("LOT-1");
                assertThat(created.expiryDate()).isEqualTo(source.expiryDate());
                assertThat(created.receivedAt()).isEqualTo(source.receivedAt());
                assertThat(created.status()).isEqualTo(StockStatus.AVAILABLE);
                assertThat(created.onHand()).isEqualTo(Quantity.of(3));
            });
            assertThat(source.onHand()).isEqualTo(Quantity.ZERO);
            assertThat(ledgerRows).singleElement().satisfies(row -> assertThat(row.to()).isEqualTo(OTHER_BIN));
        }

        @Test
        @DisplayName("onto another bin that already holds the layer as AVAILABLE: merged there")
        void mergesIntoTheLayerElsewhere() {
            UUID hold = UUID.randomUUID();
            StockItem source = stockWithHold(BIN, 0, 3, ReservationStatus.CONSUMED, hold);
            StockItem there = new StockItem(StockItemId.newId(), SOFA, OTHER_BIN, "LOT-1", source.expiryDate(),
                    Quantity.of(5), StockStatus.AVAILABLE, List.of(), 0L, source.receivedAt(), null);
            when(stockItems.findBySkuAndLocation(SOFA, OTHER_BIN)).thenReturn(List.of(there));
            when(stockItems.findByIdForUpdate(there.id())).thenReturn(Optional.of(there));

            service.putBack(command(UUID.randomUUID(), hold, OTHER_BIN, 3));

            assertThat(there.onHand()).isEqualTo(Quantity.of(8));
            assertThat(saved).containsExactly(there);
        }

        @Test
        @DisplayName("onto a bin holding the layer in another status: refused, nothing written")
        void statusMismatch() {
            UUID hold = UUID.randomUUID();
            StockItem source = stockWithHold(BIN, 0, 3, ReservationStatus.CONSUMED, hold);
            StockItem quarantined = new StockItem(StockItemId.newId(), SOFA, OTHER_BIN, "LOT-1", source.expiryDate(),
                    Quantity.of(5), StockStatus.QUARANTINE, List.of(), 0L, source.receivedAt(), null);
            when(stockItems.findBySkuAndLocation(SOFA, OTHER_BIN)).thenReturn(List.of(quarantined));
            when(stockItems.findByIdForUpdate(quarantined.id())).thenReturn(Optional.of(quarantined));

            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, OTHER_BIN, 3))))
                    .isEqualTo(ErrorCode.STOCK_STATUS_MISMATCH);
            assertThat(quarantined.onHand()).isEqualTo(Quantity.of(5));
            assertThat(ledgerRows).isEmpty();
        }

        @Test
        @DisplayName("the same line again changes nothing")
        void idempotentPerLine() {
            UUID hold = UUID.randomUUID();
            UUID line = UUID.randomUUID();
            StockItem item = stockWithHold(BIN, 7, 3, ReservationStatus.CONSUMED, hold);
            service.putBack(command(line, hold, BIN, 3));
            saved.clear();

            service.putBack(command(line, hold, BIN, 3));

            assertThat(item.onHand()).isEqualTo(Quantity.of(10));
            assertThat(ledgerRows).hasSize(1);
            assertThat(saved).isEmpty();
        }

        @Test
        @DisplayName("an unknown or unusable bin is refused before any stock changes")
        void unusableBin() {
            UUID hold = UUID.randomUUID();
            StockItem item = stockWithHold(BIN, 7, 3, ReservationStatus.CONSUMED, hold);
            when(locations.stateOf(OTHER_BIN)).thenReturn(LocationDirectory.LocationState.UNUSABLE);
            LocationId nowhere = new LocationId("HCM-ZZ9-9-Z");
            when(locations.stateOf(nowhere)).thenReturn(LocationDirectory.LocationState.UNKNOWN);

            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, OTHER_BIN, 3))))
                    .isEqualTo(ErrorCode.LOCATION_NOT_USABLE);
            assertThat(refusal(() -> service.putBack(command(UUID.randomUUID(), hold, nowhere, 3))))
                    .isEqualTo(ErrorCode.LOCATION_NOT_FOUND);
            assertThat(item.onHand()).isEqualTo(Quantity.of(7));
            assertThat(ledgerRows).isEmpty();
            assertThat(saved).isEmpty();
        }
    }
}
