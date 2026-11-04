package com.stockflow.payment.internal.service;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderDelivered;
import com.stockflow.contracts.OrderRemainderClosed;
import com.stockflow.payment.api.OrderPayables;
import com.stockflow.payment.internal.domain.CancelledOrderSettlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** SCRUM-460, kltn-docs 15 §4.4, 17 §4.5: what payment does when an order is cancelled, delivered or partly closed. */
class PaymentListenersTest {

    private static final Instant AT = Instant.parse("2026-10-22T03:00:00Z");

    private final CancelledOrderSettlement settlement = mock(CancelledOrderSettlement.class);
    private final OrderPayables orders = mock(OrderPayables.class);
    private final ReceivableService receivables = mock(ReceivableService.class);
    private final UUID orderId = UUID.randomUUID();

    private OrderCancelled cancelled(BigDecimal refundable) {
        return new OrderCancelled(orderId, "SO-5", UUID.randomUUID(), "CONFIRMED", "CUSTOMER_REQUEST", null,
                new BigDecimal("1000"), refundable, "VND", UUID.randomUUID(), AT, BigDecimal.ZERO);
    }

    private OrderRemainderClosed closed(UUID closureId, BigDecimal refundable) {
        return new OrderRemainderClosed(orderId, "SO-5", UUID.randomUUID(), "CUSTOMER_REQUEST", List.of(),
                new BigDecimal("8000000"), new BigDecimal("2000000"), new BigDecimal("3000000"), refundable, "VND",
                UUID.randomUUID(), AT, closureId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("cancelled order: lock the order first, cancel the awaited payments, ask the refundable money back as "
            + "PENDING refunds, then record any fee owed (15 §4.4)")
    void cancelledOrder() {
        OrderCancelledListener listener = new OrderCancelledListener(settlement, orders, receivables);
        OrderCancelled event = cancelled(new BigDecimal("700"));
        when(settlement.requestRefunds(any(), any(), anyString(), anyString())).thenReturn(BigDecimal.ZERO);

        listener.on(event);

        InOrder order = inOrder(orders, settlement, receivables);
        order.verify(orders).lockForPayment(orderId);
        order.verify(settlement).cancelAwaitedPayments(orderId);
        order.verify(settlement).requestRefunds(eq(orderId), eq(new BigDecimal("700")), eq("VND"),
                eq("Order SO-5 cancelled (CUSTOMER_REQUEST)"));
        order.verify(receivables).cancellationOwed(event);
    }

    @Test
    @DisplayName("cancelled order with nothing to refund (or a null amount): no refund is asked, the fee is still recorded")
    void cancelledOrderNothingToRefund() {
        OrderCancelledListener listener = new OrderCancelledListener(settlement, orders, receivables);
        OrderCancelled zero = cancelled(BigDecimal.ZERO);
        listener.on(zero);
        OrderCancelled none = cancelled(null);
        listener.on(none);

        verify(settlement, never()).requestRefunds(any(), any(), anyString(), anyString());
        verify(receivables).cancellationOwed(zero);
        verify(receivables).cancellationOwed(none);
    }

    @Test
    @DisplayName("cancelled order whose refund cannot be placed on a captured payment is left for the accountant: "
            + "no exception, the event is still handled")
    void cancelledOrderUnplacedRefund() {
        OrderCancelledListener listener = new OrderCancelledListener(settlement, orders, receivables);
        when(settlement.requestRefunds(any(), any(), anyString(), anyString())).thenReturn(new BigDecimal("50"));
        OrderCancelled event = cancelled(new BigDecimal("700"));
        listener.on(event);
        verify(receivables).cancellationOwed(event);
    }

    @Test
    @DisplayName("delivered order: handed to the receivable service")
    void deliveredOrder() {
        OrderDelivered event = new OrderDelivered(orderId, "SO-5", UUID.randomUUID(), "CREDIT", BigDecimal.TEN,
                BigDecimal.ZERO, "VND", 30, AT);
        new OrderDeliveredListener(receivables).on(event);
        verify(receivables).orderDelivered(event);
    }

    @Test
    @DisplayName("rest of an order closed: the fee owed is recorded first, then the refundable money asked back "
            + "under the order's lock, keyed by the closure")
    void remainderClosed() {
        OrderRemainderClosedListener listener = new OrderRemainderClosedListener(settlement, orders, receivables);
        UUID closure = UUID.randomUUID();
        OrderRemainderClosed event = closed(closure, new BigDecimal("1500000"));
        when(settlement.requestRemainderRefunds(any(), any(), any(), anyString(), anyString()))
                .thenReturn(BigDecimal.ZERO);

        listener.on(event);

        InOrder order = inOrder(receivables, orders, settlement);
        order.verify(receivables).remainderFeeOwed(event);
        order.verify(orders).lockForPayment(orderId);
        order.verify(settlement).requestRemainderRefunds(eq(orderId), eq(closure), eq(new BigDecimal("1500000")),
                eq("VND"), eq("Order SO-5: rest closed (CUSTOMER_REQUEST)"));
    }

    @Test
    @DisplayName("rest closed without a closure id: the order id keys the refund")
    void remainderClosedWithoutClosureId() {
        OrderRemainderClosedListener listener = new OrderRemainderClosedListener(settlement, orders, receivables);
        when(settlement.requestRemainderRefunds(any(), any(), any(), anyString(), anyString()))
                .thenReturn(BigDecimal.ZERO);
        listener.on(closed(null, BigDecimal.TEN));
        verify(settlement).requestRemainderRefunds(eq(orderId), eq(orderId), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("rest closed with no refundable money: only the fee is looked at; the order is not even locked")
    void remainderClosedNothingToRefund() {
        OrderRemainderClosedListener listener = new OrderRemainderClosedListener(settlement, orders, receivables);
        OrderRemainderClosed event = closed(UUID.randomUUID(), BigDecimal.ZERO);
        listener.on(event);
        verify(receivables).remainderFeeOwed(event);
        verifyNoInteractions(orders, settlement);
    }
}
