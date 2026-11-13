package com.stockflow.payment.internal.service;

import com.stockflow.contracts.OrderDelivered;
import com.stockflow.contracts.OrderRemainderClosed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * kltn-docs 15 §4.1 step 2, BR-09, D23: what payment does when an order is delivered or its rest closed - the
 * installments a contract order pays after delivery fall due. Since 4B-10 that is all: a cancellation and a closure
 * settle their money in the order's own transaction (4B-9, {@code OrderSettlementService}), and the money ledger's
 * receivables, refunds and fee receivables these listeners used to write went with the ledger.
 */
class PaymentListenersTest {

    private static final Instant AT = Instant.parse("2026-10-22T03:00:00Z");

    private final InstallmentService installments = mock(InstallmentService.class);
    private final UUID orderId = UUID.randomUUID();

    @Test
    @DisplayName("the whole order delivered at once (no shipment of its own): the installments after delivery fall due")
    void deliveredOrder() {
        new OrderDeliveredListener(installments).on(new OrderDelivered(orderId, "SO-5", UUID.randomUUID(), "PREPAID",
                BigDecimal.TEN, BigDecimal.ZERO, "VND", null, AT));

        verify(installments).delivered(orderId, AT, true);
        verifyNoMoreInteractions(installments);
    }

    @Test
    @DisplayName("one shipment of a split order delivered: told so, the installments decide whether all is delivered")
    void deliveredShipment() {
        new OrderDeliveredListener(installments).on(new OrderDelivered(orderId, "SO-5", UUID.randomUUID(), "PREPAID",
                BigDecimal.TEN, BigDecimal.ZERO, "VND", null, AT, UUID.randomUUID(), BigDecimal.ONE));

        verify(installments).delivered(orderId, AT, false);
    }

    @Test
    @DisplayName("rest of an order closed: delivered in full as of the closure, if that is what it left (D23)")
    void remainderClosed() {
        new OrderRemainderClosedListener(installments).on(new OrderRemainderClosed(orderId, "SO-5", UUID.randomUUID(),
                "CUSTOMER_REQUEST", List.of(), new BigDecimal("8000000"), new BigDecimal("2000000"),
                new BigDecimal("3000000"), BigDecimal.ZERO, "VND", UUID.randomUUID(), AT, UUID.randomUUID()));

        verify(installments).delivered(orderId, AT, false);
        verifyNoMoreInteractions(installments);
    }
}
