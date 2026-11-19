package com.stockflow.order.internal.controller;

import com.stockflow.common.domain.Money;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderStatusChange;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.api.PartialShipmentDecision;
import com.stockflow.order.api.RemainderClosure;
import com.stockflow.order.api.CancellationReasonCode;
import com.stockflow.order.internal.controller.dto.OrderStatusChangeResponse;
import com.stockflow.order.internal.service.PartialShipments;
import com.stockflow.order.internal.service.ShippingAddressChanges;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Review of #150, item 9: Sales' partial-shipment decisions appear in the order's history as entries of their own type,
 * never as a status change from a status to itself.
 */
class OrderHistoryEntryTypeTest {

    @Test
    @DisplayName("staff history: STATUS_CHANGE entries and a PARTIAL_SHIPMENT_DECISION entry, in time order")
    void decisionIsItsOwnEntryType() {
        UUID orderId = UUID.randomUUID();
        UUID sales = UUID.randomUUID();
        OrderService orders = mock(OrderService.class);
        PartialShipments decisions = mock(PartialShipments.class);
        IdentityService identities = mock(IdentityService.class);
        when(orders.findById(orderId)).thenReturn(Optional.of(new OrderSummary(orderId, "SO-1", UUID.randomUUID(),
                OrderStatus.PARTIALLY_FULFILLED, Money.vnd(1), List.of(), Instant.EPOCH)));
        when(orders.history(orderId)).thenReturn(List.of(
                new OrderStatusChange(OrderStatus.CONFIRMED, OrderStatus.IN_PRODUCTION, null,
                        Instant.parse("2026-10-21T01:00:00Z"), null),
                new OrderStatusChange(OrderStatus.IN_PRODUCTION, OrderStatus.PARTIALLY_FULFILLED, null,
                        Instant.parse("2026-10-21T03:00:00Z"), null)));
        when(decisions.decisions(orderId)).thenReturn(List.of(new PartialShipmentDecision(true,
                OrderStatus.IN_PRODUCTION, "Khách nhận hàng sẵn trước", sales, Instant.parse("2026-10-21T02:00:00Z"))));
        when(identities.displayNames(any())).thenReturn(Map.of(sales, "Nguyễn Hải Yến"));
        com.stockflow.order.internal.service.OrderWarehouseVisibility visibility =
                org.mockito.Mockito.mock(com.stockflow.order.internal.service.OrderWarehouseVisibility.class);
        when(visibility.visible(any(), any())).thenReturn(true);
        OrderController controller = new OrderController(orders, null, null, identities, decisions,
                mock(ShippingAddressChanges.class), visibility);
        CurrentUser staff = new CurrentUser(UUID.randomUUID(), "sales1", Set.of(Role.SALES_STAFF), Set.of(),
                DataScope.ALL, Set.of());

        List<OrderStatusChangeResponse> history = controller.history(orderId, staff).data();

        assertThat(history).extracting(OrderStatusChangeResponse::type).containsExactly(
                OrderStatusChangeResponse.STATUS_CHANGE, OrderStatusChangeResponse.PARTIAL_SHIPMENT_DECISION,
                OrderStatusChangeResponse.STATUS_CHANGE);
        OrderStatusChangeResponse decision = history.get(1);
        assertThat(decision.from()).isNull();
        assertThat(decision.to()).isNull();
        assertThat(decision.partialShipmentAllowed()).isTrue();
        assertThat(decision.reason()).isEqualTo("Khách nhận hàng sẵn trước");
        assertThat(decision.actorName()).isEqualTo("Nguyễn Hải Yến");
        assertThat(history).filteredOn(entry -> entry.from() != null && entry.from() == entry.to()).isEmpty();
    }

    @Test
    @DisplayName("staff history lists a closure of the rest as a REMAINDER_CLOSURE entry with its reason code, note and"
            + " actor - even when no status moved; a customer never sees it")
    void closureIsItsOwnEntryType() {
        UUID orderId = UUID.randomUUID();
        UUID sales = UUID.randomUUID();
        OrderService orders = mock(OrderService.class);
        PartialShipments decisions = mock(PartialShipments.class);
        IdentityService identities = mock(IdentityService.class);
        UUID customerId = UUID.randomUUID();
        when(orders.findById(orderId)).thenReturn(Optional.of(new OrderSummary(orderId, "SO-1", customerId,
                OrderStatus.PARTIALLY_FULFILLED, Money.vnd(1), List.of(), Instant.EPOCH)));
        when(orders.history(orderId)).thenReturn(List.of(
                new OrderStatusChange(OrderStatus.IN_PRODUCTION, OrderStatus.PARTIALLY_FULFILLED, null,
                        Instant.parse("2026-11-20T01:00:00Z"), null)));
        when(orders.remainderClosures(orderId)).thenReturn(List.of(new RemainderClosure(
                CancellationReasonCode.OUT_OF_STOCK, "het nap", sales, Instant.parse("2026-11-20T02:00:00Z"))));
        when(identities.displayNames(any())).thenReturn(Map.of(sales, "Nguyễn Hải Yến"));
        com.stockflow.order.internal.service.OrderWarehouseVisibility visibility =
                org.mockito.Mockito.mock(com.stockflow.order.internal.service.OrderWarehouseVisibility.class);
        when(visibility.visible(any(), any())).thenReturn(true);
        OrderController controller = new OrderController(orders, null, null, identities, decisions,
                mock(ShippingAddressChanges.class), visibility);
        CurrentUser staff = new CurrentUser(UUID.randomUUID(), "sales1", Set.of(Role.SALES_STAFF), Set.of(),
                DataScope.ALL, Set.of());

        List<OrderStatusChangeResponse> history = controller.history(orderId, staff).data();

        assertThat(history).extracting(OrderStatusChangeResponse::type).containsExactly(
                OrderStatusChangeResponse.STATUS_CHANGE, OrderStatusChangeResponse.REMAINDER_CLOSURE);
        OrderStatusChangeResponse closure = history.get(1);
        assertThat(closure.from()).isNull();
        assertThat(closure.to()).isNull();
        assertThat(closure.closureReasonCode()).isEqualTo("OUT_OF_STOCK");
        assertThat(closure.reason()).isEqualTo("het nap");
        assertThat(closure.actorName()).isEqualTo("Nguyễn Hải Yến");
        assertThat(history).filteredOn(entry -> entry.from() != null && entry.from() == entry.to()).isEmpty();
    }

    @Test
    @DisplayName("a customer's history never carries a closure of the rest, like it never carries a partial-shipment decision")
    void customerDoesNotSeeClosures() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OrderService orders = mock(OrderService.class);
        com.stockflow.customer.api.CustomerService customers = mock(com.stockflow.customer.api.CustomerService.class);
        when(customers.findByUserId(userId)).thenReturn(Optional.of(new com.stockflow.customer.api.CustomerSummary(
                customerId, userId, "A", null, null, null, null, "ACTIVE", 0, Instant.EPOCH)));
        when(orders.findById(orderId)).thenReturn(Optional.of(new OrderSummary(orderId, "SO-1", customerId,
                OrderStatus.PARTIALLY_FULFILLED, Money.vnd(1), List.of(), Instant.EPOCH)));
        when(orders.history(orderId)).thenReturn(List.of(
                new OrderStatusChange(OrderStatus.IN_PRODUCTION, OrderStatus.PARTIALLY_FULFILLED, null,
                        Instant.parse("2026-11-20T01:00:00Z"), null)));
        when(orders.remainderClosures(orderId)).thenReturn(List.of(new RemainderClosure(
                CancellationReasonCode.OUT_OF_STOCK, "het nap", UUID.randomUUID(),
                Instant.parse("2026-11-20T02:00:00Z"))));
        OrderController controller = new OrderController(orders, customers, null, mock(IdentityService.class),
                mock(PartialShipments.class), mock(ShippingAddressChanges.class),
                mock(com.stockflow.order.internal.service.OrderWarehouseVisibility.class));
        CurrentUser customer = new CurrentUser(userId, "cust", Set.of(Role.CUSTOMER), Set.of(), DataScope.OWN, Set.of());

        List<OrderStatusChangeResponse> history = controller.history(orderId, customer).data();

        assertThat(history).extracting(OrderStatusChangeResponse::type)
                .containsExactly(OrderStatusChangeResponse.STATUS_CHANGE);
    }

    @Test
    @DisplayName("WF04 G2: staff history lists a change of the delivery address as an ADDRESS_CHANGE entry with the address "
            + "before and after and who changed it, in time order")
    void addressChangeIsItsOwnEntryType() {
        UUID orderId = UUID.randomUUID();
        UUID sales = UUID.randomUUID();
        OrderService orders = mock(OrderService.class);
        IdentityService identities = mock(IdentityService.class);
        ShippingAddressChanges addresses = mock(ShippingAddressChanges.class);
        when(orders.findById(orderId)).thenReturn(Optional.of(new OrderSummary(orderId, "SO-1", UUID.randomUUID(),
                OrderStatus.ON_HOLD, Money.vnd(1), List.of(), Instant.EPOCH)));
        when(orders.history(orderId)).thenReturn(List.of(
                new OrderStatusChange(OrderStatus.READY_TO_FULFILL, OrderStatus.ON_HOLD, "ADDRESS: sai phường",
                        Instant.parse("2026-11-20T01:00:00Z"), sales)));
        OrderSummary.AddressSummary before = new OrderSummary.AddressSummary("Kho A", "0901234567", "1 Lê Lợi", null,
                "26734", "Phường Bến Nghé", "79", "TP. Hồ Chí Minh", "VN", null);
        OrderSummary.AddressSummary after = new OrderSummary.AddressSummary("Kho B", "0907654321", "9 Hai Bà Trưng", null,
                "26737", "Phường Đa Kao", "79", "TP. Hồ Chí Minh", "VN", null);
        when(addresses.changes(orderId)).thenReturn(List.of(new ShippingAddressChanges.Change(OrderStatus.ON_HOLD,
                before, after, sales, Instant.parse("2026-11-20T02:00:00Z"))));
        when(identities.displayNames(any())).thenReturn(Map.of(sales, "Nguyễn Hải Yến"));
        com.stockflow.order.internal.service.OrderWarehouseVisibility visibility =
                org.mockito.Mockito.mock(com.stockflow.order.internal.service.OrderWarehouseVisibility.class);
        when(visibility.visible(any(), any())).thenReturn(true);
        OrderController controller = new OrderController(orders, null, null, identities, mock(PartialShipments.class),
                addresses, visibility);
        CurrentUser staff = new CurrentUser(UUID.randomUUID(), "sales1", Set.of(Role.SALES_STAFF), Set.of(),
                DataScope.ALL, Set.of());

        List<OrderStatusChangeResponse> history = controller.history(orderId, staff).data();

        assertThat(history).extracting(OrderStatusChangeResponse::type).containsExactly(
                OrderStatusChangeResponse.STATUS_CHANGE, OrderStatusChangeResponse.ADDRESS_CHANGE);
        OrderStatusChangeResponse change = history.get(1);
        assertThat(change.from()).isNull();
        assertThat(change.to()).isNull();
        assertThat(change.previousAddress().line1()).isEqualTo("1 Lê Lợi");
        assertThat(change.newAddress().line1()).isEqualTo("9 Hai Bà Trưng");
        assertThat(change.newAddress().recipientName()).isEqualTo("Kho B");
        assertThat(change.actorName()).isEqualTo("Nguyễn Hải Yến");
    }
}
