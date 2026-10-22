package com.stockflow.order.internal.controller;

import com.stockflow.common.domain.Money;
import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.PermissionDeniedException;
import com.stockflow.order.api.DeliveryMode;
import com.stockflow.order.api.OrderService;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.api.OrderSummary;
import com.stockflow.order.internal.controller.dto.ReleaseOrderRequest;
import com.stockflow.order.internal.service.OrderReleases;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LP-1: releasing ({@code sales-orders:APPROVE}) does not by itself let the caller choose how the order travels - a mode
 * other than the order's also needs {@code sales-order-delivery-modes:UPDATE}; keeping the order's own needs nothing more.
 */
class ReleaseDeliveryModePermissionTest {

    private static final UUID HCM = UUID.randomUUID();

    private final OrderReleases releases = mock(OrderReleases.class);
    private final OrderService orders = mock(OrderService.class);
    private final OrderReleaseController controller = new OrderReleaseController(releases, orders);
    private final UUID orderId = UUID.randomUUID();

    private static CurrentUser holding(PermissionCode... codes) {
        return new CurrentUser(UUID.randomUUID(), "releaser", Set.of(), Set.of(codes), DataScope.ALL, Set.of());
    }

    private OrderSummary order(OrderStatus status) {
        return new OrderSummary(orderId, "SO-3", UUID.randomUUID(), status, Money.vnd(1), List.of(), Instant.EPOCH);
    }

    @Test
    @DisplayName("APPROVE alone: FULL_TRUCKLOAD for a parcel order is 403 and nothing is released; PARCEL (its own) "
            + "and no mode release")
    void approveAloneKeepsTheMode() {
        CurrentUser approver = holding(PermissionCode.of(OrderResources.ORDERS, Action.APPROVE));
        when(orders.findById(orderId)).thenReturn(Optional.of(order(OrderStatus.CONFIRMED)));
        when(releases.release(eq(orderId), eq(HCM), any(), any())).thenReturn(order(OrderStatus.READY_TO_FULFILL));

        Throwable refused = catchThrowable(() -> controller.release(orderId,
                new ReleaseOrderRequest(HCM, DeliveryMode.FULL_TRUCKLOAD), approver));
        assertThat(refused).isInstanceOf(PermissionDeniedException.class);
        verify(releases, never()).release(any(), any(), any(), any());

        controller.release(orderId, new ReleaseOrderRequest(HCM, DeliveryMode.PARCEL), approver);
        controller.release(orderId, new ReleaseOrderRequest(HCM), approver);
        verify(releases).release(orderId, HCM, DeliveryMode.PARCEL, approver.userId());
        verify(releases).release(orderId, HCM, null, approver.userId());
    }

    @Test
    @DisplayName("with sales-order-delivery-modes:UPDATE the mode goes through to the release")
    void withThePermission() {
        CurrentUser coordinator = holding(PermissionCode.of(OrderResources.ORDERS, Action.APPROVE),
                PermissionCode.of(OrderResources.DELIVERY_MODES, Action.UPDATE));
        when(releases.release(eq(orderId), eq(HCM), any(), any())).thenReturn(order(OrderStatus.READY_TO_FULFILL));

        controller.release(orderId, new ReleaseOrderRequest(HCM, DeliveryMode.FULL_TRUCKLOAD), coordinator);
        verify(releases).release(orderId, HCM, DeliveryMode.FULL_TRUCKLOAD, coordinator.userId());
        verify(orders, never()).findById(any());
    }
}
