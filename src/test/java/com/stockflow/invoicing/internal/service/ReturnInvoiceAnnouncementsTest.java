package com.stockflow.invoicing.internal.service;

import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.contracts.EInvoiceReturnUnadjusted;
import com.stockflow.contracts.ReturnResolved;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the accountants are told of a return's invoice (WF-05 BE-28, BE-44, Q-9): goods refunded for nothing still
 * reduce the invoice when the return says so (units, not money); goods waiting for their own shipment's invoice, or on
 * a replacement, are announced; once that invoice is issued, the kept pieces are taken off it.
 */
class ReturnInvoiceAnnouncementsTest {

    private static final Instant AT = Instant.parse("2026-11-20T03:00:00Z");

    private final EInvoiceCorrections corrections = mock(EInvoiceCorrections.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private static ReturnResolved returned(UUID orderLine, int pieces) {
        return new ReturnResolved(UUID.randomUUID(), "RMA-1", UUID.randomUUID(), "SO-1", UUID.randomUUID(), null,
                null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "VND", true,
                List.of(new ReturnResolved.Line(orderLine, "CUP", pieces, 0, pieces, BigDecimal.ZERO)),
                UUID.randomUUID(), AT, null, UUID.randomUUID());
    }

    @Test
    @DisplayName("BE-44: a refund of 0 dong that the return marks for a VAT adjustment (units accepted back, Q-18) is "
            + "drawn up from the units; one not marked draws nothing")
    void aZeroMoneyReturnIsAdjustedByUnits() {
        UUID line = UUID.randomUUID();
        ReturnResolved resolved = returned(line, 30);
        when(corrections.drawUpForReturn(resolved)).thenReturn(new EInvoiceCorrections.ReturnAdjustments(List.of(),
                Map.of()));

        new ReturnResolvedListener(corrections, events).on(resolved);
        verify(corrections).drawUpForReturn(resolved);

        ReturnResolved notMarked = new ReturnResolved(resolved.returnRequestId(), resolved.rmaNumber(),
                resolved.orderId(), resolved.orderNumber(), resolved.customerId(), null, null, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, "VND", false, resolved.lines(), resolved.resolvedBy(), AT, null,
                resolved.shipmentId());
        new ReturnResolvedListener(corrections, events).on(notMarked);
        verify(corrections, times(1)).drawUpForReturn(any());
    }

    @Test
    @DisplayName("BE-28, Q-9: pieces kept for their own invoice and pieces on a replacement are each announced, with "
            + "their own cause")
    void keptAndReplacementPiecesAreAnnounced() {
        UUID line = UUID.randomUUID();
        ReturnResolved resolved = returned(line, 30);
        when(corrections.drawUpForReturn(resolved)).thenReturn(new EInvoiceCorrections.ReturnAdjustments(List.of(),
                Map.of(), Map.of(line, 30L), Map.of()));
        new ReturnResolvedListener(corrections, events).on(resolved);
        when(corrections.drawUpForReturn(resolved)).thenReturn(new EInvoiceCorrections.ReturnAdjustments(List.of(),
                Map.of(), Map.of(), Map.of(line, 30L)));
        new ReturnResolvedListener(corrections, events).on(resolved);

        ArgumentCaptor<EInvoiceReturnUnadjusted> told = ArgumentCaptor.forClass(EInvoiceReturnUnadjusted.class);
        verify(events, times(2)).publishEvent(told.capture());
        assertThat(told.getAllValues()).extracting(EInvoiceReturnUnadjusted::cause).containsExactly(
                EInvoiceReturnUnadjusted.Cause.NOT_ON_AN_ISSUED_INVOICE,
                EInvoiceReturnUnadjusted.Cause.ON_A_REPLACEMENT);
        assertThat(told.getAllValues()).allSatisfy(event -> assertThat(event.pieces()).isEqualTo(30L));
    }

    @Test
    @DisplayName("BE-28: the shipment's invoice issued - its kept returns are drawn up; what it cannot take is told")
    void keptReturnsAreDrawnUpWhenTheInvoiceIsIssued() {
        UUID shipment = UUID.randomUUID();
        EInvoiceRepository.PendingReturnPieces kept = new EInvoiceRepository.PendingReturnPieces(UUID.randomUUID(),
                "RMA-2", UUID.randomUUID(), "SO-2", shipment, UUID.randomUUID(), 40);
        when(corrections.drawUpPendingReturns(shipment)).thenReturn(Map.of(kept,
                new EInvoiceCorrections.ReturnAdjustments(List.of(), Map.of(kept.orderLineId(), 10L))));
        PendingReturnAdjustmentListener listener = new PendingReturnAdjustmentListener(corrections, events,
                Clock.fixed(AT, ZoneOffset.UTC));

        listener.on(new EInvoiceIssued(UUID.randomUUID(), "EINV-1", kept.orderId(), "SO-2", shipment,
                UUID.randomUUID(), AT));

        ArgumentCaptor<EInvoiceReturnUnadjusted> told = ArgumentCaptor.forClass(EInvoiceReturnUnadjusted.class);
        verify(events).publishEvent(told.capture());
        assertThat(told.getValue().returnRequestId()).isEqualTo(kept.returnRequestId());
        assertThat(told.getValue().pieces()).isEqualTo(10L);

        listener.on(new EInvoiceIssued(UUID.randomUUID(), "EINV-FEE", kept.orderId(), "SO-2", null, null, AT));
        verify(corrections, never()).drawUpPendingReturns(null);
    }
}
