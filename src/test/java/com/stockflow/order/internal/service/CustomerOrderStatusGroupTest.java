package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderShipmentStatus;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.CancellationRequestRepository;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.repository.DeliveredAndReturnedUnits;
import com.stockflow.order.internal.repository.DeliveredAndReturnedUnits.Units;
import com.stockflow.order.internal.repository.OrderSearchRepository;
import com.stockflow.order.internal.service.CustomerOrders.DeliveryState;
import com.stockflow.order.internal.service.CustomerOrders.StatusGroup;
import com.stockflow.order.internal.service.CustomerOrders.TimelineEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BE-35 (contract F-2): an order that ended in a return is PARTIALLY_RETURNED to the customer when they kept goods - a
 * delivered shipment while the carrier brought another back, or a return (RMA) of part of what was delivered - and
 * RETURNED only when nothing stayed with them; a list row and the detail read the same.
 */
class CustomerOrderStatusGroupTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    /** The statuses an order ends a return in; CLOSED too, when it never completed. */
    private static final List<OrderStatus> RETURN_ENDED = List.of(OrderStatus.RETURNED, OrderStatus.REFUNDED,
            OrderStatus.PARTIALLY_REFUNDED);

    private static OrderSummary.Lifecycle lifecycle(Instant completedAt, List<OrderSummary.Shipment> shipments) {
        return new OrderSummary.Lifecycle(null, null, null, null, NOW, null, null, completedAt, null, null, null,
                List.of(), shipments);
    }

    private static OrderSummary.Shipment shipment(OrderShipmentStatus status, int units) {
        return new OrderSummary.Shipment(UUID.randomUUID(), status, BigDecimal.TEN, NOW, NOW, null,
                List.of(new OrderSummary.ShipmentLine(UUID.randomUUID(), units)));
    }

    private static StatusGroup group(OrderStatus status, OrderSummary.Lifecycle lifecycle, Units units) {
        return CustomerOrdersService.statusGroup(status, lifecycle,
                CustomerOrdersService.partlyReturned(status, lifecycle, units));
    }

    private static DeliveryState delivery(OrderStatus status, OrderSummary.Lifecycle lifecycle, Units units) {
        return CustomerOrdersService.deliveryState(status, lifecycle,
                CustomerOrdersService.partlyReturned(status, lifecycle, units));
    }

    @Test
    @DisplayName("a carrier brought one shipment back, the customer kept the other, then PARTIALLY_REFUNDED: "
            + "partly returned, not returned")
    void partialCarrierReturn() {
        // Shipment 1 (60 units) delivered and kept; shipment 2 (40 units) brought back by the carrier. The returned
        // shipment is not delivered and its units are not an RMA's: delivered 60, received back on returns 0.
        var lifecycle = lifecycle(null, List.of(shipment(OrderShipmentStatus.DELIVERED, 60),
                shipment(OrderShipmentStatus.RETURNED, 40)));
        Units units = new Units(1, 60, 0);

        assertThat(CustomerOrdersService.keptGoods(units)).isTrue();
        assertThat(group(OrderStatus.PARTIALLY_REFUNDED, lifecycle, units)).isEqualTo(StatusGroup.PARTIALLY_RETURNED);
        assertThat(delivery(OrderStatus.PARTIALLY_REFUNDED, lifecycle, units))
                .isEqualTo(DeliveryState.PARTIALLY_RETURNED);
    }

    @Test
    @DisplayName("partly fulfilled, one shipment delivered and the other brought back by the carrier (or lost), "
            + "nothing more to come: PARTIALLY_RETURNED, not \"preparing\" - and only then")
    void partlyFulfilledBackFromTheCarrier() {
        var lifecycle = lifecycle(null, List.of(shipment(OrderShipmentStatus.DELIVERED, 60),
                shipment(OrderShipmentStatus.RETURNED, 40)));
        // delivered 1, back 1, none open, nothing left to ship
        Units done = new Units(1, 60, 0, 1, 0, 0);
        assertThat(CustomerOrdersService.partlyReturned(OrderStatus.PARTIALLY_FULFILLED, lifecycle, done)).isTrue();
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.PARTIALLY_FULFILLED, lifecycle, true))
                .isEqualTo(StatusGroup.PARTIALLY_RETURNED);
        assertThat(CustomerOrdersService.deliveryState(OrderStatus.PARTIALLY_FULFILLED, lifecycle, true))
                .isEqualTo(DeliveryState.PARTIALLY_RETURNED);
        assertThat(CustomerOrdersService.rowDeliveryState(OrderStatus.PARTIALLY_FULFILLED, null, true, true))
                .isEqualTo(DeliveryState.PARTIALLY_RETURNED);

        assertThat(CustomerOrdersService.partlyReturned(OrderStatus.PARTIALLY_FULFILLED, lifecycle,
                new Units(1, 60, 0, 1, 0, 40))).as("its rest still to ship").isFalse();
        assertThat(CustomerOrdersService.partlyReturned(OrderStatus.PARTIALLY_FULFILLED, lifecycle,
                new Units(1, 60, 0, 1, 1, 0))).as("a shipment still with the carrier").isFalse();
        assertThat(CustomerOrdersService.partlyReturned(OrderStatus.PARTIALLY_FULFILLED, lifecycle,
                new Units(1, 60, 0, 0, 0, 40))).as("delivered in part, nothing came back: still preparing").isFalse();
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.PARTIALLY_FULFILLED, lifecycle, false))
                .isEqualTo(StatusGroup.PREPARING);
    }

    @Test
    @DisplayName("the timeline calls the carrier's PARTIALLY_FULFILLED step partly returned, not one made while preparing")
    void timelineOfACarrierReturn() {
        OrderSummary order = summary(UUID.randomUUID(), UUID.randomUUID(), OrderStatus.PARTIALLY_FULFILLED,
                lifecycle(null, List.of()));
        var history = List.of(
                new com.stockflow.order.api.OrderStatusChange(OrderStatus.PICKING, OrderStatus.PARTIALLY_FULFILLED,
                        null, NOW),
                new com.stockflow.order.api.OrderStatusChange(OrderStatus.PARTIALLY_FULFILLED, OrderStatus.SHIPPED,
                        null, NOW.plusSeconds(1)),
                new com.stockflow.order.api.OrderStatusChange(OrderStatus.SHIPPED, OrderStatus.PARTIALLY_FULFILLED,
                        "RETURNED", NOW.plusSeconds(2)));

        assertThat(CustomerOrdersService.timeline(order, history, true)).extracting(TimelineEntry::statusGroup)
                .containsExactly(StatusGroup.PREPARING, StatusGroup.SHIPPING, StatusGroup.PARTIALLY_RETURNED);
    }

    @Test
    @DisplayName("the carrier brought every shipment back - none DELIVERED: returned")
    void wholeCarrierReturn() {
        var lifecycle = lifecycle(null, List.of(shipment(OrderShipmentStatus.RETURNED, 100)));
        Units units = new Units(0, 0, 0);

        assertThat(CustomerOrdersService.keptGoods(units)).isFalse();
        for (OrderStatus status : RETURN_ENDED) {
            assertThat(group(status, lifecycle, units)).as(status.name()).isEqualTo(StatusGroup.RETURNED);
            assertThat(delivery(status, lifecycle, units)).as(status.name()).isEqualTo(DeliveryState.RETURNED);
        }
    }

    @Test
    @DisplayName("an RMA received back every delivered unit (or more): returned")
    void rmaReturnedEverything() {
        var lifecycle = lifecycle(null, List.of(shipment(OrderShipmentStatus.DELIVERED, 100)));
        for (Units units : List.of(new Units(1, 100, 100), new Units(1, 100, 120))) {
            assertThat(CustomerOrdersService.keptGoods(units)).as(units.toString()).isFalse();
            for (OrderStatus status : RETURN_ENDED) {
                assertThat(group(status, lifecycle, units)).as(status.name()).isEqualTo(StatusGroup.RETURNED);
                assertThat(delivery(status, lifecycle, units)).as(status.name()).isEqualTo(DeliveryState.RETURNED);
            }
        }
    }

    @Test
    @DisplayName("an RMA received back part of the delivered units: partly returned")
    void rmaReturnedPart() {
        var lifecycle = lifecycle(null, List.of(shipment(OrderShipmentStatus.DELIVERED, 100)));
        Units units = new Units(1, 100, 30);

        assertThat(CustomerOrdersService.keptGoods(units)).isTrue();
        for (OrderStatus status : RETURN_ENDED) {
            assertThat(group(status, lifecycle, units)).as(status.name()).isEqualTo(StatusGroup.PARTIALLY_RETURNED);
            assertThat(delivery(status, lifecycle, units)).as(status.name())
                    .isEqualTo(DeliveryState.PARTIALLY_RETURNED);
        }
    }

    @Test
    @DisplayName("CLOSED after completion is a delivery, whatever came back")
    void closedAfterCompletion() {
        var lifecycle = lifecycle(NOW, List.of(shipment(OrderShipmentStatus.DELIVERED, 100)));
        for (Units units : List.of(new Units(1, 100, 30), new Units(1, 100, 100))) {
            assertThat(group(OrderStatus.CLOSED, lifecycle, units)).isEqualTo(StatusGroup.DELIVERED);
            assertThat(delivery(OrderStatus.CLOSED, lifecycle, units)).isEqualTo(DeliveryState.DELIVERED);
        }
        assertThat(CustomerOrdersService.endedInReturn(OrderStatus.CLOSED, lifecycle)).isFalse();
    }

    @Test
    @DisplayName("CLOSED without completing: partly returned when goods were kept, returned when none were")
    void closedWithoutCompletion() {
        var lifecycle = lifecycle(null, List.of(shipment(OrderShipmentStatus.DELIVERED, 100)));
        assertThat(CustomerOrdersService.endedInReturn(OrderStatus.CLOSED, lifecycle)).isTrue();

        Units kept = new Units(1, 100, 30);
        assertThat(group(OrderStatus.CLOSED, lifecycle, kept)).isEqualTo(StatusGroup.PARTIALLY_RETURNED);
        assertThat(delivery(OrderStatus.CLOSED, lifecycle, kept)).isEqualTo(DeliveryState.PARTIALLY_RETURNED);

        Units none = new Units(1, 100, 100);
        assertThat(group(OrderStatus.CLOSED, lifecycle, none)).isEqualTo(StatusGroup.RETURNED);
        assertThat(delivery(OrderStatus.CLOSED, lifecycle, none)).isEqualTo(DeliveryState.RETURNED);
    }

    @Test
    @DisplayName("statuses that cannot be partly returned read exactly as before, the flag set or not")
    void otherStatusesUnchanged() {
        var lifecycle = lifecycle(null, List.of(shipment(OrderShipmentStatus.DELIVERED, 100)));
        for (OrderStatus status : OrderStatus.values()) {
            if (CustomerOrdersService.mayBePartlyReturned(status, lifecycle)) {
                continue;
            }
            assertThat(CustomerOrdersService.statusGroup(status, lifecycle, true)).as(status.name())
                    .isEqualTo(CustomerOrdersService.statusGroup(status, lifecycle, false));
            assertThat(CustomerOrdersService.deliveryState(status, lifecycle, true)).as(status.name())
                    .isEqualTo(CustomerOrdersService.deliveryState(status, lifecycle, false));
        }
    }

    @Test
    @DisplayName("a list row reads what the detail reads, with one units statement for the whole page")
    void listRowEqualsDetail() {
        OrderSearchRepository search = mock(OrderSearchRepository.class);
        OrderService orders = mock(OrderService.class);
        DeliveredAndReturnedUnits deliveredAndReturned = mock(DeliveredAndReturnedUnits.class);
        var service = new CustomerOrdersService(search, orders, mock(CreditChecks.class),
                mock(CancellationRequestRepository.class), mock(OrderRepository.class), mock(ReturnAllowances.class),
                deliveredAndReturned, Clock.fixed(NOW, ZoneOffset.UTC), mock(LineNames.class),
                mock(com.stockflow.design.api.SkuDesigns.class));
        UUID customer = UUID.randomUUID();

        record Case(OrderStatus status, Instant completedAt, Units units, StatusGroup group, DeliveryState delivery) {
        }
        List<Case> cases = List.of(
                new Case(OrderStatus.PARTIALLY_REFUNDED, null, new Units(1, 60, 0), StatusGroup.PARTIALLY_RETURNED,
                        DeliveryState.PARTIALLY_RETURNED),
                new Case(OrderStatus.RETURNED, null, new Units(0, 0, 0), StatusGroup.RETURNED,
                        DeliveryState.RETURNED),
                new Case(OrderStatus.REFUNDED, null, new Units(1, 100, 100), StatusGroup.RETURNED,
                        DeliveryState.RETURNED),
                new Case(OrderStatus.CLOSED, null, new Units(1, 100, 30), StatusGroup.PARTIALLY_RETURNED,
                        DeliveryState.PARTIALLY_RETURNED),
                new Case(OrderStatus.CLOSED, NOW, new Units(1, 100, 30), StatusGroup.DELIVERED,
                        DeliveryState.DELIVERED),
                new Case(OrderStatus.PARTIALLY_FULFILLED, null, new Units(1, 60, 0, 1, 0, 0),
                        StatusGroup.PARTIALLY_RETURNED, DeliveryState.PARTIALLY_RETURNED));

        List<OrderSummary> rows = new java.util.ArrayList<>();
        Map<UUID, Units> units = new java.util.HashMap<>();
        Map<UUID, Case> byId = new java.util.HashMap<>();
        for (Case c : cases) {
            UUID id = UUID.randomUUID();
            // A list row carries the milestones but no shipments; the detail carries both.
            rows.add(summary(id, customer, c.status(), lifecycle(c.completedAt(), List.of())));
            when(orders.findById(id)).thenReturn(Optional.of(summary(id, customer, c.status(),
                    lifecycle(c.completedAt(), List.of(shipment(OrderShipmentStatus.DELIVERED, 100))))));
            units.put(id, c.units());
            byId.put(id, c);
        }
        when(search.search(any(), any(Pageable.class))).thenReturn(new PageImpl<>(rows));
        // The list page and the detail read the same units, whichever orders they ask for.
        when(deliveredAndReturned.of(anyCollection())).thenAnswer(call -> {
            Map<UUID, Units> asked = new java.util.HashMap<>();
            for (Object id : (java.util.Collection<?>) call.getArgument(0)) {
                asked.put((UUID) id, units.get(id));
            }
            return asked;
        });

        var page = service.myOrders(customer, new CustomerOrders.Query(0, 20, List.of(), null, null, null, null));

        assertThat(page.items()).hasSize(cases.size());
        for (CustomerOrders.Card card : page.items()) {
            Case c = byId.get(card.orderId());
            CustomerOrders.Detail detail = service.myOrder(customer, card.orderId());
            String what = c.status() + (c.completedAt() == null ? "" : " completed") + " " + c.units();
            assertThat(card.statusGroup()).as(what).isEqualTo(c.group()).isEqualTo(detail.statusGroup());
            assertThat(card.deliveryState()).as(what).isEqualTo(c.delivery()).isEqualTo(detail.deliveryState());
        }
        // The page asked once, for the four rows that ended in a return and the partly fulfilled one (the completed
        // CLOSED one is not asked for).
        verify(deliveredAndReturned, times(1)).of(org.mockito.ArgumentMatchers.argThat(
                (java.util.Collection<UUID> ids) -> ids.size() == 5));
    }

    private static OrderSummary summary(UUID id, UUID customer, OrderStatus status, OrderSummary.Lifecycle lifecycle) {
        return new OrderSummary(id, "SO-1", customer, status, Money.vnd(1000), List.of(), NOW, null, null, null, null,
                null, null, null, null, null, null, null, lifecycle);
    }
}
