package com.stockflow.order.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.DeliveryMode;
import com.stockflow.order.api.DeliveryModeChange;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PartialShipmentDecision;
import com.stockflow.order.api.PaymentTerm;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.entity.OrderDeliveryModeChangeJpaEntity;
import com.stockflow.order.internal.entity.OrderPartialShipmentDecisionJpaEntity;
import com.stockflow.order.internal.repository.OrderDeliveryModeChangeJpaRepository;
import com.stockflow.order.internal.repository.OrderPartialShipmentDecisionJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.order.internal.service.OrderFixtures.NOW;
import static com.stockflow.order.internal.service.OrderFixtures.STAFF;
import static com.stockflow.order.internal.service.OrderFixtures.codeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The three small services that lock the order's row, ask the aggregate and write a history row: partial-shipment
 * decisions (PACK-PARTIAL), delivery-mode changes (LP-1, LP-2) and reservation bookkeeping (kltn-docs 14 BR-07).
 */
class OrderRowLockServicesTest {

    private final OrderRepository repository = mock(OrderRepository.class);
    private final OrderService orders = mock(OrderService.class);
    private final OrderSummary summary = mock(OrderSummary.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private Order locked(Order order) {
        when(repository.findByIdForUpdate(order.id())).thenReturn(Optional.of(order));
        when(orders.findById(order.id().value())).thenReturn(Optional.of(summary));
        return order;
    }

    @Nested
    @DisplayName("partial shipment decision (PACK-PARTIAL)")
    class PartialShipments {

        private final OrderPartialShipmentDecisionJpaRepository decisions =
                mock(OrderPartialShipmentDecisionJpaRepository.class);
        private final PartialShipmentService service = new PartialShipmentService(repository, decisions, orders, clock);

        @Test
        @DisplayName("allowing partial shipments saves the order and writes a decision row with the status it was in")
        void allowRecordsADecision() {
            Order order = locked(OrderFixtures.releasedPrepaid());

            assertThat(service.decide(order.id().value(), true, "customer agreed", STAFF)).isSameAs(summary);

            assertThat(order.allowPartialShipment()).isTrue();
            verify(repository).save(order);
            ArgumentCaptor<OrderPartialShipmentDecisionJpaEntity> row =
                    ArgumentCaptor.forClass(OrderPartialShipmentDecisionJpaEntity.class);
            verify(decisions).save(row.capture());
            assertThat(row.getValue().getOrderId()).isEqualTo(order.id().value());
            assertThat(row.getValue().isAllowed()).isTrue();
            assertThat(row.getValue().getOrderStatus()).isEqualTo("READY_TO_FULFILL");
            assertThat(row.getValue().getNote()).isEqualTo("customer agreed");
            assertThat(row.getValue().getDecidedBy()).isEqualTo(STAFF);
            assertThat(row.getValue().getDecidedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("the same decision again changes nothing: no save, no history row")
        void theSameDecisionIsIdempotent() {
            Order order = locked(OrderFixtures.releasedPrepaid());

            service.decide(order.id().value(), false, null, STAFF);

            verify(repository, never()).save(any());
            verify(decisions, never()).save(any());
        }

        @Test
        @DisplayName("once the order is delivered the choice is locked (ORDER_PARTIAL_SHIPMENT_LOCKED)")
        void aDeliveredOrderIsLocked() {
            Order order = locked(OrderFixtures.delivered(NOW));

            assertThat(codeOf(() -> service.decide(order.id().value(), true, null, STAFF)))
                    .isEqualTo(ErrorCode.ORDER_PARTIAL_SHIPMENT_LOCKED);
            verify(decisions, never()).save(any());
        }

        @Test
        @DisplayName("an unknown order is NOT_FOUND")
        void unknownOrder() {
            when(repository.findByIdForUpdate(any())).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.decide(UUID.randomUUID(), true, null, STAFF)))
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("the history is returned oldest first, with the status as an enum")
        void historyIsMapped() {
            UUID orderId = UUID.randomUUID();
            when(decisions.findByOrderIdOrderByDecidedAtAscIdAsc(orderId)).thenReturn(List.of(
                    new OrderPartialShipmentDecisionJpaEntity(UUID.randomUUID(), orderId, true, "PICKING", "ok",
                            STAFF, NOW)));

            List<PartialShipmentDecision> history = service.decisions(orderId);

            assertThat(history).singleElement().satisfies(d -> {
                assertThat(d.allowed()).isTrue();
                assertThat(d.orderStatus()).isEqualTo(OrderStatus.PICKING);
                assertThat(d.note()).isEqualTo("ok");
                assertThat(d.decidedBy()).isEqualTo(STAFF);
                assertThat(d.decidedAt()).isEqualTo(NOW);
            });
        }
    }

    @Nested
    @DisplayName("delivery mode (LP-1, LP-2)")
    class DeliveryModes {

        private final OrderDeliveryModeChangeJpaRepository changes = mock(OrderDeliveryModeChangeJpaRepository.class);
        private final DeliveryModeHistory history = new DeliveryModeHistory(changes, clock);
        private final DeliveryModeService service = new DeliveryModeService(repository, changes, history, orders);

        @Test
        @DisplayName("changing the mode saves the order and writes a row with the previous mode and the order's status")
        void changeIsRecorded() {
            Order order = locked(OrderFixtures.releasedPrepaid());
            assertThat(order.deliveryMode()).isNotEqualTo(DeliveryMode.FULL_TRUCKLOAD);

            assertThat(service.change(order.id().value(), DeliveryMode.FULL_TRUCKLOAD, "whole truck", STAFF))
                    .isSameAs(summary);

            assertThat(order.deliveryMode()).isEqualTo(DeliveryMode.FULL_TRUCKLOAD);
            verify(repository).save(order);
            ArgumentCaptor<OrderDeliveryModeChangeJpaEntity> row =
                    ArgumentCaptor.forClass(OrderDeliveryModeChangeJpaEntity.class);
            verify(changes).save(row.capture());
            assertThat(row.getValue().getDeliveryMode()).isEqualTo("FULL_TRUCKLOAD");
            assertThat(row.getValue().getPreviousMode()).isNotEqualTo("FULL_TRUCKLOAD");
            assertThat(row.getValue().getOrderStatus()).isEqualTo("READY_TO_FULFILL");
            assertThat(row.getValue().getChangedBy()).isEqualTo(STAFF);
            assertThat(row.getValue().getChangedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("choosing the mode the order already has records nothing")
        void sameModeAgain() {
            Order order = locked(OrderFixtures.releasedPrepaid());

            service.change(order.id().value(), order.deliveryMode(), null, STAFF);

            verify(repository, never()).save(any());
            verify(changes, never()).save(any());
        }

        @Test
        @DisplayName("once packing started the mode is locked (DELIVERY_MODE_LOCKED), nothing is written")
        void lockedOncePackingStarted() {
            Order order = locked(OrderFixtures.releasedPrepaid());
            order.recordPackingStarted(NOW);

            assertThat(codeOf(() -> service.change(order.id().value(), DeliveryMode.FULL_TRUCKLOAD, null, STAFF)))
                    .isEqualTo(ErrorCode.DELIVERY_MODE_LOCKED);
            verify(changes, never()).save(any());
        }

        @Test
        @DisplayName("a full truckload is refused while the carrier would collect the balance (LP-2)")
        void fullTruckloadNeedsNoCod() {
            Order order = OrderFixtures.draft(PaymentTerm.DEPOSIT, UUID.randomUUID());
            order.submit();
            locked(order);

            assertThat(codeOf(() -> service.change(order.id().value(), DeliveryMode.FULL_TRUCKLOAD, null, STAFF)))
                    .isEqualTo(ErrorCode.DELIVERY_MODE_FTL_NO_COD);
            verify(changes, never()).save(any());
        }

        @Test
        @DisplayName("an unknown order is NOT_FOUND")
        void unknownOrder() {
            when(repository.findByIdForUpdate(any(OrderId.class))).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.change(UUID.randomUUID(), DeliveryMode.FULL_TRUCKLOAD, null, STAFF)))
                    .isEqualTo(ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("the change history maps both modes and the status back to enums")
        void historyIsMapped() {
            UUID orderId = UUID.randomUUID();
            when(changes.findByOrderIdOrderByChangedAtAscIdAsc(orderId)).thenReturn(List.of(
                    new OrderDeliveryModeChangeJpaEntity(UUID.randomUUID(), orderId, "FULL_TRUCKLOAD",
                            DeliveryMode.PARCEL.name(), "CONFIRMED", "n", STAFF, NOW)));

            List<DeliveryModeChange> list = service.changes(orderId);

            assertThat(list).singleElement().satisfies(c -> {
                assertThat(c.deliveryMode()).isEqualTo(DeliveryMode.FULL_TRUCKLOAD);
                assertThat(c.previousMode()).isEqualTo(DeliveryMode.PARCEL);
                assertThat(c.orderStatus()).isEqualTo(OrderStatus.CONFIRMED);
                assertThat(c.changedBy()).isEqualTo(STAFF);
            });
        }
    }

    @Nested
    @DisplayName("reservations held by an order")
    class Reservations {

        private final OrderReservationService service = new OrderReservationService(repository);

        @Test
        @DisplayName("production holds are added to the line under the order lock and saved")
        void productionHoldsAreAdded() {
            Order order = locked(OrderFixtures.releasedPrepaid());
            UUID line = order.lines().get(0).id();
            UUID hold = UUID.randomUUID();

            service.addProductionHolds(order.id().value(), line, List.of(hold));

            assertThat(order.reservationIds()).contains(hold);
            verify(repository).save(order);
        }

        @Test
        @DisplayName("no reservation ids: the order is not even loaded")
        void emptyHoldsAreIgnored() {
            service.addProductionHolds(UUID.randomUUID(), UUID.randomUUID(), List.of());
            service.addProductionHolds(UUID.randomUUID(), UUID.randomUUID(), null);

            verify(repository, never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("a hold is replaced by the allocations that took its place, keeping the other holds")
        void aHoldIsReplaced() {
            Order order = locked(OrderFixtures.releasedPrepaid());
            UUID old = order.reservationIds().get(0);
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();

            service.replaceHold(order.id().value(), old, List.of(first, second));

            assertThat(order.reservationIds()).doesNotContain(old).contains(first, second);
            verify(repository).save(order);
        }

        @Test
        @DisplayName("replacing a hold the order does not have changes and saves nothing")
        void aForeignHoldIsIgnored() {
            Order order = locked(OrderFixtures.releasedPrepaid());
            List<UUID> before = order.reservationIds();

            service.replaceHold(order.id().value(), UUID.randomUUID(), List.of(UUID.randomUUID()));

            assertThat(order.reservationIds()).isEqualTo(before);
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("an unknown order is NOT_FOUND for both operations")
        void unknownOrder() {
            when(repository.findByIdForUpdate(any(OrderId.class))).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.addProductionHolds(UUID.randomUUID(), UUID.randomUUID(),
                    List.of(UUID.randomUUID())))).isEqualTo(ErrorCode.NOT_FOUND);
            assertThat(codeOf(() -> service.replaceHold(UUID.randomUUID(), UUID.randomUUID(),
                    List.of(UUID.randomUUID())))).isEqualTo(ErrorCode.NOT_FOUND);
        }
    }
}
