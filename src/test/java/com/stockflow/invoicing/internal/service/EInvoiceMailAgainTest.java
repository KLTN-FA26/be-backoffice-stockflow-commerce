package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.contracts.EInvoiceMailRequested;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.ErrorNoticeRepository;
import com.stockflow.invoicing.internal.domain.InvoiceParty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 audit r2 M-1 (decision §1.5 step 3, HĐ-8 step 2 "Sale được báo, bổ sung e-mail → gửi lại. Kết quả: thư trong
 * Mailpit"): an invoice issued while the customer had no invoice e-mail reaches the address Sales records afterwards -
 * its first mail when it was recorded in time, and "send again" in every case - and an invoice whose signature does
 * not hold is never mailed.
 */
class EInvoiceMailAgainTest {

    private static final InvoiceParty NO_EMAIL = new InvoiceParty("Cong ty Tra Sua", "0398765432",
            "12 Nguyen Hue, P. Ben Nghe, TP HCM", null);

    private final EInvoiceRepository invoices = mock(EInvoiceRepository.class);
    private final EInvoiceDrafts drafts = mock(EInvoiceDrafts.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final EInvoiceMails mails = new EInvoiceMails(invoices, drafts, events,
            Clock.fixed(EInvoiceFixtures.NOW, ZoneOffset.UTC));
    private final EInvoiceDirectoryAdapter directory = new EInvoiceDirectoryAdapter(invoices,
            mock(ErrorNoticeRepository.class), mock(FileStorage.class), drafts);

    private EInvoice issuedWithoutAnEmail() {
        EInvoice invoice = EInvoiceFixtures.issued(EInvoiceFixtures.draft(NO_EMAIL), 589);
        when(invoices.findById(invoice.id())).thenReturn(Optional.of(invoice));
        return invoice;
    }

    @Test
    @DisplayName("issued without an e-mail, Sales then records one: sent again to it, the request published once")
    void sentAgainToTheEmailSalesRecorded() {
        EInvoice invoice = issuedWithoutAnEmail();
        when(drafts.currentInvoiceEmail(invoice.snapshot().customerId())).thenReturn("ke.toan@trasua.example.com");

        EInvoiceMails.Resend resend = mails.resend(invoice.id(), null);

        assertThat(resend.recipient()).isEqualTo("ke.toan@trasua.example.com");
        ArgumentCaptor<EInvoiceMailRequested> published = ArgumentCaptor.forClass(EInvoiceMailRequested.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getValue().einvoiceId()).isEqualTo(invoice.id());
        assertThat(published.getValue().requestedAt()).isEqualTo(EInvoiceFixtures.NOW);
        assertThat(directory.mailAgain(invoice.id())).hasValueSatisfying(mail ->
                assertThat(mail.recipientEmail()).isEqualTo("ke.toan@trasua.example.com"));
        assertThat(directory.mail(invoice.id())).as("the first mail, if still to be sent, finds it too")
                .hasValueSatisfying(mail -> assertThat(mail.recipientEmail()).isEqualTo("ke.toan@trasua.example.com"));
    }

    @Test
    @DisplayName("still no e-mail anywhere: refused, naming what is missing - nothing published, nothing mailed")
    void refusedWhileThereIsNoEmail() {
        EInvoice invoice = issuedWithoutAnEmail();

        BusinessException refused = catchThrowableOfType(BusinessException.class,
                () -> mails.resend(invoice.id(), null));

        assertThat(refused.errorCode()).isEqualTo(ErrorCode.EINVOICE_BUYER_PROFILE_INCOMPLETE);
        assertThat(refused.details()).containsEntry("missing", List.of("EMAIL"));
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("an invoice that names an address goes there first; sent again, to the customer's address now")
    void theInvoicesAddressFirstTheCurrentOneWhenSentAgain() {
        EInvoice invoice = EInvoiceFixtures.issued(EInvoiceFixtures.draft(), 590);
        when(invoices.findById(invoice.id())).thenReturn(Optional.of(invoice));
        when(drafts.currentInvoiceEmail(invoice.snapshot().customerId())).thenReturn("moi@trasua.example.com");

        assertThat(directory.mail(invoice.id())).hasValueSatisfying(mail ->
                assertThat(mail.recipientEmail()).isEqualTo(EInvoiceFixtures.BUYER.email()));
        assertThat(directory.mailAgain(invoice.id())).hasValueSatisfying(mail ->
                assertThat(mail.recipientEmail()).isEqualTo("moi@trasua.example.com"));
    }

    @Test
    @DisplayName("HĐ-8: never mailed - first or again - while its seller's signature does not hold, nor before issue")
    void anInvoiceWithABadSignatureOrADraftIsNeverMailed() {
        EInvoice invoice = EInvoiceFixtures.issued(EInvoiceFixtures.draft(), 591);
        invoice.signatureInvalid();
        when(invoices.findById(invoice.id())).thenReturn(Optional.of(invoice));
        when(drafts.currentInvoiceEmail(any())).thenReturn("ke.toan@trasua.example.com");

        assertThat(catchThrowableOfType(BusinessException.class, () -> mails.resend(invoice.id(), null))
                .errorCode()).isEqualTo(ErrorCode.EINVOICE_INVALID_TRANSITION);
        assertThat(directory.mail(invoice.id())).isEmpty();
        assertThat(directory.mailAgain(invoice.id())).isEmpty();
        assertThat(directory.files(invoice.id())).isEmpty();

        EInvoice draft = EInvoiceFixtures.draft();
        when(invoices.findById(draft.id())).thenReturn(Optional.of(draft));
        assertThat(catchThrowableOfType(BusinessException.class, () -> mails.resend(draft.id(), null))
                .errorCode()).isEqualTo(ErrorCode.EINVOICE_NOT_ISSUED);
        verify(events, never()).publishEvent(any());
    }
}
