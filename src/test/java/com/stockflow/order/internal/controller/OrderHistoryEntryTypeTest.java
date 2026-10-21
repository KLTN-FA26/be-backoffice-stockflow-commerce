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
import com.stockflow.order.internal.controller.dto.OrderStatusChangeResponse;
import com.stockflow.order.internal.service.PartialShipments;
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
        OrderController controller = new OrderController(orders, null, null, null, identities, decisions);
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
}
