package com.stockflow.order.internal.service;

import com.stockflow.common.error.ErrorCode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PartialShipmentDecision;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.entity.OrderPartialShipmentDecisionJpaEntity;
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
 * The small services that lock the order's row and ask the aggregate: partial-shipment decisions (PACK-PARTIAL), which
 * also write a history row, and reservation bookkeeping (kltn-docs 14 BR-07).
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
    @DisplayName("reservations held by an order")
    class Reservations {

        private final OrderReservationService service = new OrderReservationService(repository);

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
        @DisplayName("an unknown order is NOT_FOUND")
        void unknownOrder() {
            when(repository.findByIdForUpdate(any(OrderId.class))).thenReturn(Optional.empty());

            assertThat(codeOf(() -> service.replaceHold(UUID.randomUUID(), UUID.randomUUID(),
                    List.of(UUID.randomUUID())))).isEqualTo(ErrorCode.NOT_FOUND);
        }
    }
}
