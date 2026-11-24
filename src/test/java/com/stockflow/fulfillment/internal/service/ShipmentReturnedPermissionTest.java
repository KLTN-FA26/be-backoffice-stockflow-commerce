package com.stockflow.fulfillment.internal.service;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.PermissionCode;
import com.stockflow.common.security.PermissionDeniedException;
import com.stockflow.common.security.Role;
import com.stockflow.fulfillment.internal.domain.ShipmentStatus;
import com.stockflow.fulfillment.internal.entity.ShipmentJpaEntity;
import com.stockflow.fulfillment.internal.repository.CarrierJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadJpaRepository;
import com.stockflow.fulfillment.internal.repository.DispatchLoadShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.PackageJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentEventJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentJpaRepository;
import com.stockflow.fulfillment.internal.repository.ShipmentLocks;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.api.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static com.stockflow.fulfillment.internal.service.FulfillmentFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * WF-05 BE-14 (decision Q-7, audit C-4): recording that the goods came back (RETURNED) - final, closing the shipment's
 * units - takes {@code fulfillment-delivery-outcomes:APPROVE}, as delivered and not delivered do; UPDATE alone was
 * enough before. Checked in the service every caller passes through, before anything is locked or told.
 */
class ShipmentReturnedPermissionTest {

    private final ShipmentLocks locks = mock(ShipmentLocks.class);
    private final OrderService orders = mock(OrderService.class);
    private final ShipmentService service = new ShipmentService(mock(ShipmentJpaRepository.class), locks,
            mock(ShipmentEventJpaRepository.class), mock(PackageJpaRepository.class), mock(PickJpaRepository.class),
            mock(CarrierJpaRepository.class), mock(DispatchLoadJpaRepository.class),
            mock(DispatchLoadShipmentJpaRepository.class), mock(ShipmentTimeline.class), mock(ShipmentContext.class),
            orders, mock(IdentityService.class),
            mock(com.stockflow.fulfillment.internal.repository.DeliveryOutcomeCorrectionJpaRepository.class),
            mock(com.stockflow.fulfillment.internal.repository.FailureReasonCorrectionJpaRepository.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    private static CurrentUser holding(Set<PermissionCode> permissions) {
        return new CurrentUser(UUID.randomUUID(), "user", Set.of(Role.WAREHOUSE_STAFF), permissions, DataScope.ALL,
                Set.of(), Set.of());
    }

    @Test
    @DisplayName("UPDATE alone: RETURNED is 403, nothing locked, the order not told")
    void returnedNeedsApprove() {
        CurrentUser updateOnly = holding(Set.of(PermissionCode.of(FulfillmentPermissions.DELIVERY_OUTCOMES,
                Action.UPDATE)));
        UUID shipment = UUID.randomUUID();

        assertThatThrownBy(() -> service.recordOutcome(shipment, new Shipments.Outcome("RETURNED", null, null),
                updateOnly)).isInstanceOf(PermissionDeniedException.class);
        assertThatThrownBy(() -> service.recordOutcome(shipment, new Shipments.Outcome("DELIVERED", null, null),
                updateOnly)).isInstanceOf(PermissionDeniedException.class);
        verify(locks, never()).lock(any());
        verifyNoInteractions(orders);
    }

    @Test
    @DisplayName("APPROVE: RETURNED is recorded")
    void approveRecordsReturned() {
        CurrentUser manager = holding(Set.of(PermissionCode.of(FulfillmentPermissions.DELIVERY_OUTCOMES,
                Action.APPROVE)));
        ShipmentJpaEntity row = FulfillmentFixtures.rowIn(ShipmentStatus.DELIVERY_FAILED);
        org.mockito.Mockito.when(locks.lock(Set.of(row.getId()))).thenReturn(java.util.List.of(row));
        var known = new com.stockflow.order.api.OrderSummary.Shipment(row.getId(),
                com.stockflow.order.api.OrderShipmentStatus.DELIVERY_FAILED, java.math.BigDecimal.ZERO, NOW, null,
                null, java.util.List.of(), null);
        var order = FulfillmentFixtures.order(row.getOrderId(), java.util.List.of(known));
        org.mockito.Mockito.when(orders.findById(row.getOrderId())).thenReturn(java.util.Optional.of(order));
        org.mockito.Mockito.when(orders.recordShipmentReturned(any(), any(), any(java.time.Instant.class), any()))
                .thenReturn(order);

        service.recordOutcome(row.getId(), new Shipments.Outcome("RETURNED", null, null), manager);

        assertThat(row.getStatus()).isEqualTo(ShipmentStatus.RETURNED);
    }
}
