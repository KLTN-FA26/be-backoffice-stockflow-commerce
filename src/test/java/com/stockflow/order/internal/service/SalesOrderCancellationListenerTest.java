package com.stockflow.order.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.OrderCancelled;
import com.stockflow.order.internal.domain.Order;
import com.stockflow.order.internal.domain.OrderId;
import com.stockflow.order.internal.domain.OrderRepository;
import com.stockflow.order.internal.domain.ProductionOrder;
import com.stockflow.order.internal.domain.ProductionOrderRepository;
import com.stockflow.order.internal.domain.ProductionOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 4A-6 (BR-09, 17 §4.8 steps 30 and 32): what the cascade does with each production order of a cancelled order - the
 * real database run is {@code AwaitingStockIntegrationTest}; here, the decisions and the failures.
 */
class SalesOrderCancellationListenerTest {

    private static final Instant NOW = Instant.parse("2026-11-09T03:00:00Z");
    private static final UUID ORDER = UUID.randomUUID();
    private static final UUID SALES = UUID.randomUUID();

    private final ProductionOrderService service = mock(ProductionOrderService.class);
    private final ProductionOrderRepository productionOrders = mock(ProductionOrderRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final SalesOrderCancellationListener listener = new SalesOrderCancellationListener(service,
            productionOrders, orders, mock(PlatformTransactionManager.class));

    private static ProductionOrder production(ProductionOrderStatus status, long version) {
        return new ProductionOrder(UUID.randomUUID(), "PRD-20261109-%04d".formatted(version), ORDER, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 12, 1), status, false,
                false, null, null, "VND", null, UUID.randomUUID(), null, null, null, null, null, null, null, null, null,
                null, null, null, List.of(), version);
    }

    private static OrderCancelled cancelled(UUID by, String note) {
        return new OrderCancelled(ORDER, "SO-20261109-000046", UUID.randomUUID(), "AWAITING_STOCK", "CUSTOMER_REQUEST",
                note, BigDecimal.ZERO, BigDecimal.ZERO, "VND", by, NOW);
    }

    private void stored(ProductionOrder... all) {
        when(productionOrders.ofSalesOrder(ORDER)).thenReturn(List.of(all));
        for (ProductionOrder production : all) {
            when(productionOrders.findByIdForUpdate(production.id())).thenReturn(Optional.of(production));
        }
        when(orders.findByIdForUpdate(new OrderId(ORDER))).thenReturn(Optional.of(mock(Order.class)));
    }

    @Test
    @DisplayName("not printing yet: cancelled; printing or partly received: closed; finished: left - each by who "
            + "cancelled the order, the sales order locked first")
    void cancelsClosesOrLeaves() {
        ProductionOrder draft = production(ProductionOrderStatus.DRAFT, 1);
        ProductionOrder sample = production(ProductionOrderStatus.SAMPLE_PENDING, 2);
        ProductionOrder printing = production(ProductionOrderStatus.IN_PRODUCTION, 3);
        ProductionOrder partly = production(ProductionOrderStatus.PARTIALLY_RECEIVED, 4);
        ProductionOrder received = production(ProductionOrderStatus.RECEIVED, 5);
        ProductionOrder gone = production(ProductionOrderStatus.CANCELLED, 6);
        stored(draft, sample, printing, partly, received, gone);

        listener.on(cancelled(SALES, "Customer changed supplier"));

        String reason = "Sales order SO-20261109-000046 was cancelled: CUSTOMER_REQUEST - Customer changed supplier";
        verify(service).cancel(draft.id(), 1, reason, SALES);
        verify(service).cancel(sample.id(), 2, reason, SALES);
        verify(service).close(printing.id(), 3, reason, SALES);
        verify(service).close(partly.id(), 4, reason, SALES);
        verify(service, never()).cancel(eq(received.id()), anyLong(), anyString(), any());
        verify(service, never()).close(eq(gone.id()), anyLong(), anyString(), any());
        verify(productionOrders, never()).findByIdForUpdate(received.id());
        InOrder lockOrder = inOrder(orders, productionOrders);
        lockOrder.verify(orders).findByIdForUpdate(new OrderId(ORDER));
        lockOrder.verify(productionOrders).findByIdForUpdate(draft.id());
        verify(orders, times(4)).findByIdForUpdate(new OrderId(ORDER));
    }

    @Test
    @DisplayName("one refused does not stop the others; the failure is raised once all were tried, for a redelivery")
    void failureRaisedAfterTheRest() {
        ProductionOrder first = production(ProductionOrderStatus.SENT, 1);
        ProductionOrder second = production(ProductionOrderStatus.ACCEPTED, 2);
        stored(first, second);
        BusinessException changed = new BusinessException(ErrorCode.OPTIMISTIC_LOCK, "changed");
        when(service.cancel(eq(first.id()), anyLong(), anyString(), any())).thenThrow(changed);

        assertThatThrownBy(() -> listener.on(cancelled(SALES, null))).isSameAs(changed);

        verify(service).cancel(second.id(), 2, "Sales order SO-20261109-000046 was cancelled: CUSTOMER_REQUEST", SALES);
    }

    @Test
    @DisplayName("nothing open: nothing locked; cancelled by no one: left to the coordinator")
    void nothingToDo() {
        stored(production(ProductionOrderStatus.RECEIVED, 1), production(ProductionOrderStatus.CLOSED, 2));
        listener.on(cancelled(SALES, null));
        verifyNoInteractions(service);
        verify(orders, never()).findByIdForUpdate(any());

        stored(production(ProductionOrderStatus.SENT, 3));
        listener.on(cancelled(null, null));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("the reason is cut to what a production order keeps (500), never in the middle of a character")
    void reasonFitsTheColumn() {
        String note = "x".repeat(600);
        assertThat(SalesOrderCancellationListener.reasonOf(cancelled(SALES, note))).hasSize(ProductionOrder.TEXT_MAX)
                .startsWith("Sales order SO-20261109-000046 was cancelled: CUSTOMER_REQUEST - xxx");
        String emoji = "a".repeat(ProductionOrder.TEXT_MAX - 1 - "Sales order SO-20261109-000046 was cancelled: "
                .length() - "CUSTOMER_REQUEST - ".length()) + "😀tail";
        String cut = SalesOrderCancellationListener.reasonOf(cancelled(SALES, emoji));
        assertThat(cut).hasSize(ProductionOrder.TEXT_MAX - 1);
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
        assertThat(SalesOrderCancellationListener.reasonOf(cancelled(SALES, "  "))).as("a blank note is no note")
                .isEqualTo("Sales order SO-20261109-000046 was cancelled: CUSTOMER_REQUEST");
    }
}
