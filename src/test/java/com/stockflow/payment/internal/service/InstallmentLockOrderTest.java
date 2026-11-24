package com.stockflow.payment.internal.service;

import com.stockflow.payment.api.InstallmentOrders;
import com.stockflow.payment.internal.domain.InstallmentBook;
import com.stockflow.payment.internal.repository.InstallmentSearch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WF-05 BE-41 (audit L-15): every change a payment listener makes to an order's installments takes the order's row lock
 * first and reads the order's facts under it, then locks the installments - the order every money movement keeps.
 * Read before the lock, a correction committing in between let a milestone be reached from a delivery that no longer
 * existed.
 */
class InstallmentLockOrderTest {

    private static final UUID ORDER = UUID.randomUUID();
    private static final Instant AT = Instant.parse("2026-11-20T03:00:00Z");

    private final InstallmentBook book = mock(InstallmentBook.class);
    private final InstallmentOrders orders = mock(InstallmentOrders.class);
    private final InstallmentService service = new InstallmentService(book, mock(InstallmentSearch.class), orders,
            new CompanyBankProperties("Bank", "0001", "StockFlow"), Clock.fixed(AT, ZoneOffset.UTC));

    private void lockedFirst(Consumer<InstallmentService> change, boolean readsFacts) {
        when(orders.batchesDeliveredAt(ORDER)).thenReturn(Map.of());
        when(orders.deliveredInFullAt(ORDER)).thenReturn(Optional.of(AT));
        when(book.lockOfOrder(ORDER)).thenReturn(List.of());
        change.accept(service);
        InOrder order = inOrder(orders, book);
        order.verify(orders).lockForPayment(ORDER);
        if (readsFacts) {
            order.verify(orders).batchesDeliveredAt(ORDER);
            order.verify(orders).deliveredInFullAt(ORDER);
        }
        order.verify(book).lockOfOrder(ORDER);
    }

    @Test
    @DisplayName("delivered: the order, its facts under the lock, then the installments")
    void delivered() {
        lockedFirst(s -> s.delivered(ORDER, AT, false), true);
    }

    @Test
    @DisplayName("delivered as a whole order: the order, then the installments")
    void deliveredWhole() {
        lockedFirst(s -> s.delivered(ORDER, AT, true), false);
    }

    @Test
    @DisplayName("a delivery corrected: the order, its facts under the lock, then the installments")
    void deliveryCorrected() {
        lockedFirst(s -> s.deliveryCorrected(ORDER), true);
    }

    @Test
    @DisplayName("a shipment brought back: the order, its facts under the lock, then the installments")
    void shipmentReturnSettled() {
        lockedFirst(s -> s.shipmentReturnSettled(ORDER), true);
    }

    @Test
    @DisplayName("released: the order, then the installments")
    void released() {
        lockedFirst(s -> s.released(ORDER, AT, null), false);
    }
}
