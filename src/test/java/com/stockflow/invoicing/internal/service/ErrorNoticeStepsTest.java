package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.EInvoiceErrorNoticeRecorded;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.NoticeOutcome;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.NoticeReceipt;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.ErrorNotice;
import com.stockflow.invoicing.internal.domain.ErrorNoticeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.invoicing.internal.domain.EInvoiceFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 integration item b and HĐ-6: a 04/SS notice tells the buyer when - and only when - it is about the buyer's name
 * or address <b>and</b> the provider took it in; a notice is recorded only for an invoice whose provider can send it. A
 * notice beside an adjustment or a replacement leaves the telling to that correction's own issued invoice.
 */
class ErrorNoticeStepsTest {

    private final ErrorNoticeRepository notices = mock(ErrorNoticeRepository.class);
    private final EInvoiceRepository invoices = mock(EInvoiceRepository.class);
    private final EInvoiceProviders providers = mock(EInvoiceProviders.class);
    private final EInvoiceProvider provider = mock(EInvoiceProvider.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ErrorNoticeSteps steps = new ErrorNoticeSteps(notices, invoices, providers,
            new InvoicingProperties(Duration.ofDays(1), null, null, new InvoicingProperties.Corrections(true)), events,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID customer = UUID.randomUUID();
    private final EInvoice invoice = EInvoiceFixtures.issuedFor(customer, 42);

    @BeforeEach
    void aProviderThatSendsNotices() {
        when(providers.find(invoice.provider())).thenReturn(Optional.of(provider));
        when(provider.sendsErrorNotices()).thenReturn(true);
    }

    private void found(EInvoice.Corrections corrections) {
        when(invoices.findByIdForUpdate(invoice.id())).thenReturn(Optional.of(invoice));
        when(invoices.findById(invoice.id())).thenReturn(Optional.of(invoice));
        when(invoices.correctionsOf(invoice.id())).thenReturn(corrections);
    }

    /** Records a notice and keeps what was inserted, so the answer can be applied to it. */
    private ErrorNotice record(ErrorNotice.Subject subject, String name) {
        UUID id = steps.record(invoice.id(), subject, "Sai mot chu trong ten", name, null, UUID.randomUUID());
        var inserted = ArgumentCaptor.forClass(ErrorNotice.class);
        verify(notices).insert(inserted.capture());
        assertThat(inserted.getValue().id()).isEqualTo(id);
        when(notices.findByIdForUpdate(id)).thenReturn(Optional.of(inserted.getValue()));
        return inserted.getValue();
    }

    @Test
    @DisplayName("a wrong buyer name: recorded and told nothing; the buyer is told once the provider took it in")
    void aWrongNameTellsTheBuyerOnceSent() {
        found(EInvoice.Corrections.NONE);

        ErrorNotice notice = record(ErrorNotice.Subject.BUYER_NAME_ADDRESS, "Cong ty Tra Sua Ngon");
        verify(events, never()).publishEvent(any(Object.class));

        steps.answered(notice.id(), new NoticeReceipt(NoticeOutcome.PENDING, null, null));

        var published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        EInvoice.Snapshot issued = invoice.snapshot();
        assertThat(published.getValue()).isEqualTo(new EInvoiceErrorNoticeRecorded(notice.id(), issued.id(),
                issued.code(), issued.orderId(), issued.orderNumber(), customer, NOW));

        steps.answered(notice.id(), new NoticeReceipt(NoticeOutcome.ACCEPTED, "TB-1", null));
        verify(events).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("a notice the provider refused outright notified nobody: the buyer is not told it was")
    void aNoticeRefusedOutrightTellsNobody() {
        found(EInvoice.Corrections.NONE);
        ErrorNotice notice = record(ErrorNotice.Subject.BUYER_NAME_ADDRESS, "Cong ty Tra Sua Ngon");

        steps.answered(notice.id(), new NoticeReceipt(NoticeOutcome.REJECTED, "NOTICE_REJECTED", "Sai mau"));

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("a notice beside an adjustment: recorded, and nothing told - the adjustment's invoice tells the buyer")
    void aNoticeBesideACorrectionTellsNobody() {
        found(new EInvoice.Corrections(true, false, new BigDecimal("-40000")));

        ErrorNotice notice = record(ErrorNotice.Subject.WITH_CORRECTION, null);
        steps.answered(notice.id(), new NoticeReceipt(NoticeOutcome.ACCEPTED, "TB-1", null));

        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("HĐ-6: a provider that cannot send the form gets no notice recorded - it is filed on a portal")
    void aProviderWithoutNoticesRefusesTheNotice() {
        found(EInvoice.Corrections.NONE);
        when(provider.sendsErrorNotices()).thenReturn(false);

        assertThatThrownBy(() -> steps.record(invoice.id(), ErrorNotice.Subject.BUYER_NAME_ADDRESS,
                "Sai mot chu trong ten", "Cong ty Tra Sua Ngon", null, UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.EINVOICE_ERROR_NOTICE_NOT_SUPPORTED));
        verify(notices, never()).insert(any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("HĐ-6: the buyer's portal lists only notices the provider took in")
    void theBuyerSeesNoDraftNotice() {
        found(EInvoice.Corrections.NONE);
        ErrorNotice.Snapshot draft = new ErrorNotice.Snapshot(UUID.randomUUID(), invoice.id(), ErrorNotice.FORM_CODE,
                ErrorNotice.Subject.BUYER_NAME_ADDRESS, "Sai ten", "Ten dung", null, ErrorNotice.Status.DRAFT, null,
                null, null, null, null, NOW);
        ErrorNotice.Snapshot sent = new ErrorNotice.Snapshot(UUID.randomUUID(), invoice.id(), ErrorNotice.FORM_CODE,
                ErrorNotice.Subject.BUYER_NAME_ADDRESS, "Sai ten", "Ten dung", null, ErrorNotice.Status.SENT, NOW,
                null, null, null, null, NOW);
        when(notices.findByInvoice(invoice.id())).thenReturn(List.of(draft, sent));

        assertThat(steps.toBuyer(customer, invoice.id())).containsExactly(sent);
    }
}
