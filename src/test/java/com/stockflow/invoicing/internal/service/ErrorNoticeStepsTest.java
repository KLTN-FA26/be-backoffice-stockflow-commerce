package com.stockflow.invoicing.internal.service;

import com.stockflow.contracts.EInvoiceErrorNoticeRecorded;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.ErrorNotice;
import com.stockflow.invoicing.internal.domain.ErrorNoticeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.invoicing.internal.domain.EInvoiceFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 integration item b: recording a 04/SS notice tells the buyer when - and only when - the notice is about the
 * buyer's name or address. A notice beside an adjustment or a replacement leaves the telling to that correction's own
 * issued invoice.
 */
class ErrorNoticeStepsTest {

    private final ErrorNoticeRepository notices = mock(ErrorNoticeRepository.class);
    private final EInvoiceRepository invoices = mock(EInvoiceRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ErrorNoticeSteps steps = new ErrorNoticeSteps(notices, invoices,
            new InvoicingProperties(Duration.ofDays(1), null, null, new InvoicingProperties.Corrections(true)), events,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID customer = UUID.randomUUID();
    private final EInvoice invoice = EInvoiceFixtures.issuedFor(customer, 42);

    private void found(EInvoice.Corrections corrections) {
        when(invoices.findByIdForUpdate(invoice.id())).thenReturn(Optional.of(invoice));
        when(invoices.correctionsOf(invoice.id())).thenReturn(corrections);
    }

    @Test
    @DisplayName("a wrong buyer name: recorded, and the buyer is told in the same transaction")
    void aWrongNameTellsTheBuyer() {
        found(EInvoice.Corrections.NONE);

        UUID id = steps.record(invoice.id(), ErrorNotice.Subject.BUYER_NAME_ADDRESS, "Sai mot chu trong ten",
                "Cong ty Tra Sua Ngon", null, UUID.randomUUID());

        verify(notices).insert(any(ErrorNotice.class));
        var published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        EInvoice.Snapshot issued = invoice.snapshot();
        assertThat(published.getValue()).isEqualTo(new EInvoiceErrorNoticeRecorded(id, issued.id(), issued.code(),
                issued.orderId(), issued.orderNumber(), customer, NOW));
    }

    @Test
    @DisplayName("a notice beside an adjustment: recorded, and nothing told - the adjustment's invoice tells the buyer")
    void aNoticeBesideACorrectionTellsNobody() {
        found(new EInvoice.Corrections(true, false, new BigDecimal("-40000")));

        steps.record(invoice.id(), ErrorNotice.Subject.WITH_CORRECTION, "Kem hoa don dieu chinh", null, null,
                UUID.randomUUID());

        verify(notices).insert(any(ErrorNotice.class));
        verify(events, never()).publishEvent(any(Object.class));
    }
}
