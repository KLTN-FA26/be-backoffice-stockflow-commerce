package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.EInvoiceDraftFailed;
import com.stockflow.contracts.EInvoiceReturnUnadjusted;
import com.stockflow.contracts.OrderRemainderClosed;
import com.stockflow.contracts.ReturnResolved;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WF-05 integration items a and c: what invoicing tells the accountant when the document an event asks for cannot be
 * drawn up. Both listeners swallow the refusal (the event is not retried, nothing will change on a retry) and turn it
 * into an event the notification module puts in front of a person.
 */
class InvoicingEventListenersTest {

    private static final Instant AT = Instant.parse("2026-11-12T03:00:00Z");

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    @Nested
    @DisplayName("the rest of the shipping fee, when an order's remainder is closed")
    class ShippingFeeRest {

        private final EInvoiceDrafts drafts = mock(EInvoiceDrafts.class);
        private final OrderRemainderClosedListener listener = new OrderRemainderClosedListener(drafts, events);
        private final OrderRemainderClosed closed = new OrderRemainderClosed(UUID.randomUUID(), "SO-20261101-0007",
                UUID.randomUUID(), "CUSTOMER_REQUEST", List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, "VND", UUID.randomUUID(), AT, UUID.randomUUID());

        @Test
        @DisplayName("a refusal announces a failed draft that names the order and no shipment")
        void aRefusalIsAnnouncedWithoutAShipment() {
            when(drafts.drawUpShippingFeeRest(closed.orderId(), AT))
                    .thenThrow(new BusinessException(ErrorCode.EINVOICE_NOT_INVOICEABLE));

            listener.on(closed);

            var published = ArgumentCaptor.forClass(Object.class);
            verify(events).publishEvent(published.capture());
            assertThat(published.getValue()).isEqualTo(new EInvoiceDraftFailed(closed.orderId(),
                    "SO-20261101-0007", null, "EINVOICE_NOT_INVOICEABLE"));
        }

        @Test
        @DisplayName("nothing left to invoice: nothing to announce")
        void nothingLeft() {
            when(drafts.drawUpShippingFeeRest(closed.orderId(), AT)).thenReturn(Optional.empty());

            listener.on(closed);

            verifyNoInteractions(events);
        }
    }

    @Nested
    @DisplayName("adjustments for a resolved return")
    class Returns {

        private final EInvoiceCorrections corrections = mock(EInvoiceCorrections.class);
        private final ReturnResolvedListener listener = new ReturnResolvedListener(corrections, events);
        private final UUID lineA = UUID.randomUUID();
        private final UUID lineB = UUID.randomUUID();

        private ReturnResolved resolved(boolean vat) {
            return new ReturnResolved(UUID.randomUUID(), "RMA-20261112-0001", UUID.randomUUID(), "SO-20261101-0007",
                    UUID.randomUUID(), null, null, BigDecimal.ZERO, new BigDecimal("300000"), BigDecimal.ZERO, "VND",
                    vat, List.of(
                            new ReturnResolved.Line(lineA, "CUP-12OZ", 50, 0, 50, new BigDecimal("100000")),
                            new ReturnResolved.Line(lineB, "LID-90", 100, 0, 100, new BigDecimal("200000")),
                            new ReturnResolved.Line(lineB, "LID-90", 20, 20, 0, BigDecimal.ZERO)),
                    UUID.randomUUID(), AT);
        }

        private EInvoiceReturnUnadjusted announced() {
            var published = ArgumentCaptor.forClass(Object.class);
            verify(events).publishEvent(published.capture());
            return (EInvoiceReturnUnadjusted) published.getValue();
        }

        @Test
        @DisplayName("refunded goods no issued invoice carries: NOT_ON_AN_ISSUED_INVOICE with what is left")
        void unplacedGoodsAreAnnounced() {
            ReturnResolved event = resolved(true);
            when(corrections.drawUpForReturn(event)).thenReturn(
                    new EInvoiceCorrections.ReturnAdjustments(List.of(), Map.of(lineA, 30L)));

            listener.on(event);

            EInvoiceReturnUnadjusted sent = announced();
            assertThat(sent.cause()).isEqualTo(EInvoiceReturnUnadjusted.Cause.NOT_ON_AN_ISSUED_INVOICE);
            assertThat(sent.orderLines()).isEqualTo(1);
            assertThat(sent.pieces()).isEqualTo(30);
            assertThat(sent.reasonCode()).isNull();
            assertThat(sent.returnRequestId()).isEqualTo(event.returnRequestId());
            assertThat(sent.resolvedAt()).isEqualTo(AT);
        }

        @Test
        @DisplayName("a refusal: REFUSED with its code and every refunded piece, by order line")
        void aRefusalIsAnnounced() {
            ReturnResolved event = resolved(true);
            when(corrections.drawUpForReturn(event))
                    .thenThrow(new BusinessException(ErrorCode.EINVOICE_PROVIDER_CANNOT_CORRECT));

            listener.on(event);

            EInvoiceReturnUnadjusted sent = announced();
            assertThat(sent.cause()).isEqualTo(EInvoiceReturnUnadjusted.Cause.REFUSED);
            assertThat(sent.reasonCode()).isEqualTo("EINVOICE_PROVIDER_CANNOT_CORRECT");
            assertThat(sent.orderLines()).as("a line with no credited pieces is not owed").isEqualTo(2);
            assertThat(sent.pieces()).isEqualTo(150);
        }

        @Test
        @DisplayName("everything adjusted, or no VAT adjustment asked for: nothing to announce")
        void nothingOwed() {
            ReturnResolved event = resolved(true);
            when(corrections.drawUpForReturn(event))
                    .thenReturn(new EInvoiceCorrections.ReturnAdjustments(List.of(), Map.of()));

            listener.on(event);
            listener.on(resolved(false));

            verify(corrections, times(1)).drawUpForReturn(any());
            verifyNoInteractions(events);
        }

        @Test
        @DisplayName("the return's adjustments commit on their own: a failure in them never rolls back the caller")
        void drawnUpInATransactionOfTheirOwn() throws NoSuchMethodException {
            Transactional tx = EInvoiceCorrections.class.getMethod("drawUpForReturn", ReturnResolved.class)
                    .getAnnotation(Transactional.class);

            assertThat(tx).isNotNull();
            assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        }
    }
}
