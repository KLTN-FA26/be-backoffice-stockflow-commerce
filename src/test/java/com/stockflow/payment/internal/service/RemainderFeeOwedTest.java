package com.stockflow.payment.internal.service;

import com.stockflow.contracts.OrderRemainderClosed;
import com.stockflow.payment.internal.domain.Receivable;
import com.stockflow.payment.internal.domain.ReceivableBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review of #150, item 1: the part of a closure fee the money received did not cover - deposit 3M of 10M, 2M shipped,
 * a 2M fee: 1M kept from the deposit, 1M owed - is the customer's receivable, one per closure.
 */
class RemainderFeeOwedTest {

    private static OrderRemainderClosed closed(UUID closure, BigDecimal owed) {
        return new OrderRemainderClosed(UUID.randomUUID(), "SO-1", UUID.randomUUID(), "CUSTOMER_REQUEST", List.of(),
                new BigDecimal("8000000"), new BigDecimal("2000000"), new BigDecimal("3000000"), BigDecimal.ZERO, "VND",
                UUID.randomUUID(), Instant.parse("2026-10-22T03:00:00Z"), closure, new BigDecimal("2000000"),
                new BigDecimal("1000000"), owed);
    }

    @Test
    @DisplayName("1M owed: a CANCELLATION_FEE receivable of 1M, due on the closing day, keyed by the closure; again: none")
    void owedFeeIsAReceivable() {
        ReceivableBook book = mock(ReceivableBook.class);
        when(book.save(any(Receivable.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ReceivableService service = new ReceivableService(book, null, null, null, null, Clock.systemUTC());
        UUID closure = UUID.randomUUID();
        OrderRemainderClosed event = closed(closure, new BigDecimal("1000000"));
        when(book.findReceivableOfShipment(event.orderId(), closure)).thenReturn(Optional.empty());

        service.remainderFeeOwed(event);

        ArgumentCaptor<Receivable> saved = ArgumentCaptor.forClass(Receivable.class);
        verify(book).save(saved.capture());
        assertThat(saved.getValue().amount()).isEqualByComparingTo("1000000");
        assertThat(saved.getValue().kind()).isEqualTo("CANCELLATION_FEE");
        assertThat(saved.getValue().shipmentId()).isEqualTo(closure);
        assertThat(saved.getValue().dueDate()).isEqualTo(LocalDate.of(2026, 10, 22));

        ReceivableBook again = mock(ReceivableBook.class);
        when(again.findReceivableOfShipment(event.orderId(), closure)).thenReturn(Optional.of(saved.getValue()));
        new ReceivableService(again, null, null, null, null, Clock.systemUTC()).remainderFeeOwed(event);
        verify(again, never()).save(any(Receivable.class));

        ReceivableBook none = mock(ReceivableBook.class);
        new ReceivableService(none, null, null, null, null, Clock.systemUTC())
                .remainderFeeOwed(closed(UUID.randomUUID(), BigDecimal.ZERO));
        verify(none, never()).save(any(Receivable.class));
    }
}
