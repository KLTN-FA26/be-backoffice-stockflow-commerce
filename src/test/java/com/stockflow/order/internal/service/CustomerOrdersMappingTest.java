package com.stockflow.order.internal.service;

import com.stockflow.common.domain.Money;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.domain.CreditChecks;
import com.stockflow.order.internal.service.CustomerOrders.DeliveryState;
import com.stockflow.order.internal.service.CustomerOrders.ProductionState;
import com.stockflow.order.internal.service.CustomerOrders.StatusGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What a customer reads of an order (SCRUM-245/264): the folded status, delivery, production and hold wording. */
class CustomerOrdersMappingTest {

    private static final java.time.Instant NOW = java.time.Instant.parse("2026-10-19T03:00:00Z");

    private static final Instant T0 = Instant.parse("2026-09-20T03:00:00Z");
    private static final UUID DESIGN = UUID.randomUUID();

    private static OrderSummary.LineSummary line(UUID id, int quantity, UUID design) {
        return new OrderSummary.LineSummary(id, "SOFA-3S-GREY", quantity, Money.vnd(1000), Money.vnd(1000L * quantity),
                List.of(), design, design == null ? null : "a".repeat(64));
    }

    private static CustomerOrders.Production production(OrderSummary.LineSummary line, OrderStatus status, int produced) {
        return CustomerOrdersService.production(line, status, Map.of(line.lineId(), produced));
    }

    private static CreditChecks.Check check(CreditChecks.Outcome outcome, Instant decidedAt) {
        return new CreditChecks.Check(UUID.randomUUID(), T0, BigDecimal.TEN, BigDecimal.ONE, BigDecimal.TEN, outcome,
                decidedAt == null ? null : UUID.randomUUID(), decidedAt, "internal note");
    }

    private static OrderSummary summary(OrderStatus status, OrderSummary.Cancellation cancellation) {
        return new OrderSummary(UUID.randomUUID(), "SO-1", UUID.randomUUID(), status, Money.vnd(1000), List.of(), T0,
                null, null, null, null, null, null, null, null, null, null, null, null, cancellation);
    }

    @Test
    @DisplayName("every order status folds into a group")
    void everyStatusHasAGroup() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(CustomerOrdersService.statusGroup(status)).as(status.name()).isNotNull();
        }
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.DRAFT)).isEqualTo(StatusGroup.AWAITING_PAYMENT);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.PENDING_PAYMENT)).isEqualTo(StatusGroup.AWAITING_PAYMENT);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.ON_HOLD)).isEqualTo(StatusGroup.UNDER_REVIEW);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.CONFIRMED)).isEqualTo(StatusGroup.CONFIRMED);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.IN_PRODUCTION)).isEqualTo(StatusGroup.IN_PRODUCTION);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.READY_TO_FULFILL)).isEqualTo(StatusGroup.PREPARING);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.PICKING)).isEqualTo(StatusGroup.PREPARING);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.SHIPPED)).isEqualTo(StatusGroup.SHIPPING);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.DELIVERED)).isEqualTo(StatusGroup.DELIVERED);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.COMPLETED)).isEqualTo(StatusGroup.DELIVERED);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.CANCELLED)).isEqualTo(StatusGroup.CANCELLED);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.RETURNED)).isEqualTo(StatusGroup.RETURNED);
    }

    @Test
    @DisplayName("delivery state is only what the status records, when no shipment is known")
    void deliveryStates() {
        for (OrderStatus status : OrderStatus.values()) {
            DeliveryState expected = switch (status) {
                case SHIPPED, PARTIALLY_FULFILLED -> DeliveryState.IN_TRANSIT;
                case DELIVERY_FAILED -> DeliveryState.DELIVERY_FAILED;
                case DELIVERED, COMPLETED, RETURN_REQUESTED, CLOSED -> DeliveryState.DELIVERED;
                case RETURNED, REFUNDED, PARTIALLY_REFUNDED -> DeliveryState.RETURNED;
                default -> DeliveryState.NOT_SHIPPED;
            };
            assertThat(CustomerOrdersService.deliveryState(status)).as(status.name()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("G3-2: every status has a group the portal knows; new ones fold into the old set")
    void newStatusesFoldIntoTheOldGroups() {
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.PARTIALLY_FULFILLED)).isEqualTo(StatusGroup.PREPARING);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.PACKED)).isEqualTo(StatusGroup.PREPARING);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.DELIVERY_FAILED)).isEqualTo(StatusGroup.SHIPPING);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.RETURN_REQUESTED)).isEqualTo(StatusGroup.RETURNED);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.REFUNDED)).isEqualTo(StatusGroup.RETURNED);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.PARTIALLY_REFUNDED)).isEqualTo(StatusGroup.RETURNED);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.CLOSED)).isEqualTo(StatusGroup.DELIVERED);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.CLOSED, lifecycle(null, List.of())))
                .as("closed after a refund, never completed").isEqualTo(StatusGroup.RETURNED);
        assertThat(CustomerOrdersService.statusGroup(OrderStatus.CLOSED, lifecycle(NOW, List.of())))
                .as("closed after completion").isEqualTo(StatusGroup.DELIVERED);
    }

    @Test
    @DisplayName("G3-2 split shipments: the delivery state sums the shipments up (09 BR-05)")
    void deliveryStateOfSplitShipments() {
        var delivered = shipment(com.stockflow.order.api.OrderShipmentStatus.DELIVERED);
        var inTransit = shipment(com.stockflow.order.api.OrderShipmentStatus.HANDED_OVER);
        var failed = shipment(com.stockflow.order.api.OrderShipmentStatus.DELIVERY_FAILED);
        assertThat(CustomerOrdersService.deliveryState(OrderStatus.SHIPPED, lifecycle(null, List.of(delivered, inTransit))))
                .isEqualTo(DeliveryState.PARTIALLY_DELIVERED);
        assertThat(CustomerOrdersService.deliveryState(OrderStatus.SHIPPED, lifecycle(null, List.of(inTransit))))
                .isEqualTo(DeliveryState.IN_TRANSIT);
        assertThat(CustomerOrdersService.deliveryState(OrderStatus.DELIVERY_FAILED, lifecycle(null, List.of(delivered, failed))))
                .isEqualTo(DeliveryState.PARTIALLY_DELIVERED);
        assertThat(CustomerOrdersService.deliveryState(OrderStatus.DELIVERY_FAILED, lifecycle(null, List.of(failed))))
                .isEqualTo(DeliveryState.DELIVERY_FAILED);
        assertThat(CustomerOrdersService.deliveryState(OrderStatus.PARTIALLY_FULFILLED, lifecycle(null, List.of(inTransit))))
                .isEqualTo(DeliveryState.IN_TRANSIT);
        assertThat(CustomerOrdersService.deliveryState(OrderStatus.ON_HOLD, lifecycle(null, List.of(delivered))))
                .isEqualTo(DeliveryState.PARTIALLY_DELIVERED);
        assertThat(CustomerOrdersService.deliveryState(OrderStatus.ON_HOLD, lifecycle(null, List.of())))
                .isEqualTo(DeliveryState.NOT_SHIPPED);
    }

    private static com.stockflow.order.api.OrderSummary.Lifecycle lifecycle(java.time.Instant completedAt,
            List<com.stockflow.order.api.OrderSummary.Shipment> shipments) {
        return new com.stockflow.order.api.OrderSummary.Lifecycle(null, null, null, null, null, null, null, completedAt,
                null, null, null, null, null, List.of(), shipments);
    }

    private static com.stockflow.order.api.OrderSummary.Shipment shipment(
            com.stockflow.order.api.OrderShipmentStatus status) {
        return new com.stockflow.order.api.OrderSummary.Shipment(UUID.randomUUID(), status, java.math.BigDecimal.TEN,
                java.math.BigDecimal.ZERO, NOW, null, null, List.of());
    }

    @Test
    @DisplayName("a stock line needs no production, whatever the order status")
    void stockLineNeedsNoProduction() {
        var stock = line(UUID.randomUUID(), 5, null);
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(production(stock, status, 0).state()).as(status.name()).isEqualTo(ProductionState.NOT_REQUIRED);
        }
    }

    @Test
    @DisplayName("a print line: not started, in progress, done, cancelled")
    void printLineStates() {
        var print = line(UUID.randomUUID(), 10, DESIGN);

        assertThat(production(print, OrderStatus.CONFIRMED, 0)).isEqualTo(
                new CustomerOrders.Production(ProductionState.NOT_STARTED, 0));
        assertThat(production(print, OrderStatus.IN_PRODUCTION, 0)).isEqualTo(
                new CustomerOrders.Production(ProductionState.IN_PROGRESS, 0));
        assertThat(production(print, OrderStatus.IN_PRODUCTION, 4)).isEqualTo(
                new CustomerOrders.Production(ProductionState.IN_PROGRESS, 4));
        // The good units cover the line before the order has moved on.
        assertThat(production(print, OrderStatus.IN_PRODUCTION, 10)).isEqualTo(
                new CustomerOrders.Production(ProductionState.DONE, 10));
        // Past production the line is done even if no completion was recorded.
        assertThat(production(print, OrderStatus.READY_TO_FULFILL, 0).state()).isEqualTo(ProductionState.DONE);
        assertThat(production(print, OrderStatus.SHIPPED, 0).state()).isEqualTo(ProductionState.DONE);
        assertThat(production(print, OrderStatus.COMPLETED, 0).state()).isEqualTo(ProductionState.DONE);
        assertThat(production(print, OrderStatus.CANCELLED, 4)).isEqualTo(
                new CustomerOrders.Production(ProductionState.CANCELLED, 4));
        // A line the production module reported nothing about.
        assertThat(CustomerOrdersService.production(print, OrderStatus.IN_PRODUCTION, Map.of()).goodQuantity()).isZero();
    }

    @Test
    @DisplayName("the hold is described as awaiting credit approval only while a credit decision is open")
    void holdReason() {
        assertThat(CustomerOrdersService.holdReasonPublic(OrderStatus.ON_HOLD,
                Optional.of(check(CreditChecks.Outcome.OVER_LIMIT, null)))).isEqualTo("AWAITING_CREDIT_APPROVAL");
        assertThat(CustomerOrdersService.holdReasonPublic(OrderStatus.ON_HOLD,
                Optional.of(check(CreditChecks.Outcome.OVERDUE, null)))).isEqualTo("AWAITING_CREDIT_APPROVAL");

        // Decided, or not a credit shortfall, or no credit check at all: just under review.
        assertThat(CustomerOrdersService.holdReasonPublic(OrderStatus.ON_HOLD,
                Optional.of(check(CreditChecks.Outcome.OVER_LIMIT, T0)))).isEqualTo("UNDER_REVIEW");
        assertThat(CustomerOrdersService.holdReasonPublic(OrderStatus.ON_HOLD,
                Optional.of(check(CreditChecks.Outcome.WITHIN_LIMIT, null)))).isEqualTo("UNDER_REVIEW");
        assertThat(CustomerOrdersService.holdReasonPublic(OrderStatus.ON_HOLD, Optional.empty())).isEqualTo("UNDER_REVIEW");

        // Not on hold: nothing to say, even if an old credit check is still open.
        assertThat(CustomerOrdersService.holdReasonPublic(OrderStatus.CONFIRMED,
                Optional.of(check(CreditChecks.Outcome.OVER_LIMIT, null)))).isNull();
    }

    @Test
    @DisplayName("the timeline skips unknown steps, takes only the cancellation's reason code, and never the free text")
    void timeline() {
        var cancelled = summary(OrderStatus.CANCELLED, new OrderSummary.Cancellation(
                CancellationReasonCode.CUSTOMER_REQUEST, "OTHER: staff note about the customer", null));
        var history = List.of(
                new OrderStatusChange(null, OrderStatus.PENDING_PAYMENT, null, T0),
                new OrderStatusChange(OrderStatus.PENDING_PAYMENT, null, null, T0.plusSeconds(1)),
                new OrderStatusChange(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED, "OTHER: staff note about the customer",
                        T0.plusSeconds(2)));

        var timeline = CustomerOrdersService.timeline(cancelled, history);

        assertThat(timeline).extracting(CustomerOrders.TimelineEntry::status)
                .containsExactly(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED);
        assertThat(timeline).extracting(CustomerOrders.TimelineEntry::statusGroup)
                .containsExactly(StatusGroup.AWAITING_PAYMENT, StatusGroup.CANCELLED);
        assertThat(timeline).extracting(CustomerOrders.TimelineEntry::reasonCode)
                .containsExactly(null, CancellationReasonCode.CUSTOMER_REQUEST);
        assertThat(timeline.toString()).doesNotContain("staff note");
    }

    @Test
    @DisplayName("an order with no history reads as one entry: the status it is in, when it was placed")
    void timelineWithoutHistory() {
        var order = summary(OrderStatus.CONFIRMED, null);

        assertThat(CustomerOrdersService.timeline(order, List.of())).containsExactly(
                new CustomerOrders.TimelineEntry(OrderStatus.CONFIRMED, StatusGroup.CONFIRMED, T0, null));
    }
}
