package com.stockflow.order.internal.controller;

import com.stockflow.common.security.CurrentUser;
import com.stockflow.common.security.DataScope;
import com.stockflow.common.security.Role;
import com.stockflow.customer.api.CustomerLabel;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.order.api.DeliveryFailureReason;
import com.stockflow.order.api.OrderStatus;
import com.stockflow.order.internal.controller.dto.CompletionHoldResponse;
import com.stockflow.order.internal.controller.dto.DeliveryFailureCaseResponse;
import com.stockflow.order.internal.domain.CompletionHoldReason;
import com.stockflow.order.internal.domain.DeliveryFailureCase;
import com.stockflow.order.internal.service.DeliveryFailureService;
import com.stockflow.order.internal.service.OrderCompletionHolds;
import com.stockflow.order.internal.service.OrderWarehouseVisibility;
import com.stockflow.warehouse.api.WarehouseService;
import com.stockflow.warehouse.api.WarehouseView;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A11: the completion hold and the delivery failure cases name the people, the customer and the warehouse beside their
 * ids - each looked up once for the whole answer, never once per row.
 */
class OrderActorNamesTest {

    private final CurrentUser sales = new CurrentUser(UUID.randomUUID(), "sales1", Set.of(Role.SALES_STAFF), Set.of(),
            DataScope.ALL, Set.of());

    @Test
    @DisplayName("a completion hold names who held and who released it; an open hold has no releaser's name")
    void completionHoldNamesItsPeople() {
        UUID orderId = UUID.randomUUID();
        UUID heldBy = UUID.randomUUID();
        OrderCompletionHolds holds = mock(OrderCompletionHolds.class);
        when(holds.current(orderId)).thenReturn(Optional.of(new OrderCompletionHolds.CompletionHold(UUID.randomUUID(),
                orderId, CompletionHoldReason.DELIVERY_DISPUTED, "checking", Instant.now(), heldBy, null, null, null)));
        IdentityService identities = mock(IdentityService.class);
        when(identities.displayNames(any())).thenReturn(Map.of(heldBy, "Nguyen Sales"));

        CompletionHoldResponse hold = new OrderCompletionHoldController(holds, mock(OrderWarehouseVisibility.class),
                identities).current(orderId, sales).data();

        assertThat(hold.heldByName()).isEqualTo("Nguyen Sales");
        assertThat(hold.releasedByName()).isNull();
    }

    @Test
    @DisplayName("the delivery failure list names who decided and released each case, its customer and its warehouse, "
            + "from one lookup each for the whole list")
    void deliveryFailuresNameTheirReferences() {
        UUID warehouseId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID decidedBy = UUID.randomUUID();
        DeliveryFailureService failures = mock(DeliveryFailureService.class);
        when(failures.list(any(), any(), anyInt())).thenReturn(List.of(
                view(warehouseId, customerId, decidedBy), view(warehouseId, customerId, null)));
        IdentityService identities = mock(IdentityService.class);
        when(identities.displayNames(any())).thenReturn(Map.of(decidedBy, "Tran Decider"));
        CustomerService customers = mock(CustomerService.class);
        when(customers.labels(any())).thenReturn(Map.of(customerId,
                new CustomerLabel(customerId, "Cong ty A", "0312345678")));
        WarehouseService warehouses = mock(WarehouseService.class);
        when(warehouses.findWarehouses(any())).thenReturn(Map.of(warehouseId,
                new WarehouseView(warehouseId, "HCM", "Kho HCM", "Somewhere", true)));

        List<DeliveryFailureCaseResponse> cases = new DeliveryFailureController(failures,
                mock(OrderWarehouseVisibility.class), identities, customers, warehouses).list(null, 50, sales).data();

        assertThat(cases).hasSize(2).allSatisfy(c -> {
            assertThat(c.customerName()).isEqualTo("Cong ty A");
            assertThat(c.customerCode()).isEqualTo("0312345678");
            assertThat(c.warehouseCode()).isEqualTo("HCM");
            assertThat(c.warehouseName()).isEqualTo("Kho HCM");
            assertThat(c.releasedByName()).isNull();
        });
        assertThat(cases.get(0).decidedByName()).isEqualTo("Tran Decider");
        assertThat(cases.get(1).decidedByName()).isNull();
        verify(identities, times(1)).displayNames(any());
        verify(customers, times(1)).labels(any());
        verify(warehouses, times(1)).findWarehouses(any());
    }

    private static DeliveryFailureService.CaseView view(UUID warehouseId, UUID customerId, UUID decidedBy) {
        DeliveryFailureCase failure = new DeliveryFailureCase(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, UUID.randomUUID(), "RR-1", warehouseId, DeliveryFailureReason.CUSTOMER_ABSENT, null,
                Map.of(UUID.randomUUID(), 4), List.of(), Instant.now(),
                decidedBy == null ? DeliveryFailureCase.Status.OPEN : DeliveryFailureCase.Status.CANCELLED, decidedBy,
                decidedBy == null ? null : Instant.now(), decidedBy == null ? null : "cancelled", null, null, false,
                null, null, null, null, null, null, 0L);
        return new DeliveryFailureService.CaseView(failure, "SO-1", customerId, OrderStatus.RETURNED, Map.of(), false);
    }
}
