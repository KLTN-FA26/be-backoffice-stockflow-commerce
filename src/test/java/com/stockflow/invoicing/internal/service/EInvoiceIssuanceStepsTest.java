package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.RecordRetention;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.contracts.EInvoiceNeedsAttention;
import com.stockflow.invoicing.internal.domain.CorrectionBasis;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.InvoiceSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review finding 5: an invoice is marked issued only once its XML and PDF are stored and locked for their ten years.
 * A lock that fails leaves it waiting for the provider's answer, to be reconciled again.
 */
class EInvoiceIssuanceStepsTest {

    private static final String XML_KEY = "einvoices/2026/11/12/00000000-0000-7000-8000-000000000001.xml";
    private static final String PDF_KEY = "einvoices/2026/11/12/00000000-0000-7000-8000-000000000002.pdf";
    private static final EInvoiceProvider.Result ISSUED = new EInvoiceProvider.Result(EInvoiceProvider.ResultState.ISSUED,
            42, "SBXCODE", "SBXLOOK", EInvoiceFixtures.NOW, null, null);
    private static final EInvoiceProvider.Documents DOCUMENTS = new EInvoiceProvider.Documents(
            "<HDon/>".getBytes(StandardCharsets.UTF_8), "%PDF-1.7".getBytes(StandardCharsets.UTF_8));

    private final EInvoiceRepository invoices = mock(EInvoiceRepository.class);
    private final FileTransfers files = mock(FileTransfers.class);
    private final InvoiceSettings settings = mock(InvoiceSettings.class);
    private final EInvoiceProviders providers = mock(EInvoiceProviders.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final EInvoiceIssuanceSteps steps = new EInvoiceIssuanceSteps(invoices, settings, providers, files, events,
            Clock.fixed(EInvoiceFixtures.NOW, ZoneOffset.UTC));
    private EInvoice invoice;

    @BeforeEach
    void anInvoiceAtTheProvider() {
        invoice = EInvoiceFixtures.in(EInvoiceStatus.SUBMITTING);
        when(invoices.findByIdForUpdate(invoice.id())).thenReturn(Optional.of(invoice));
        when(invoices.findById(invoice.id())).thenReturn(Optional.of(invoice));
        when(invoices.save(any())).thenAnswer(call -> call.getArgument(0));
        when(files.store(eq(FileCategory.EINVOICE), any(FileUpload.class))).thenAnswer(call -> {
            FileUpload upload = call.getArgument(1);
            return new StoredFile(upload.contentType().endsWith("xml") ? XML_KEY : PDF_KEY, upload.originalName(),
                    upload.contentType(), upload.sizeBytes(), EInvoiceFixtures.NOW);
        });
    }

    @Test
    @DisplayName("both files are stored and locked, then the invoice is issued - in that order")
    void storedLockedThenIssued() {
        steps.issued(invoice.id(), invoice.transactionId(), ISSUED, DOCUMENTS);

        InOrder order = inOrder(files, invoices);
        order.verify(files).retainNow(XML_KEY, RecordRetention.EINVOICE);
        order.verify(files).retainNow(PDF_KEY, RecordRetention.EINVOICE);
        order.verify(invoices).save(invoice);
        assertThat(invoice.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(invoice.snapshot().invoiceNumber()).isEqualTo(42);
        assertThat(invoice.xmlFileKey()).isEqualTo(XML_KEY);
        assertThat(invoice.pdfFileKey()).isEqualTo(PDF_KEY);
        verify(files, never()).retainAfterCommit(any(), any());
    }

    @Test
    @DisplayName("a lock that fails fails the step: the invoice is not issued and nothing is saved")
    void aFailedLockLeavesItWaiting() {
        doThrow(new StorageException("Object Lock was not applied")).when(files).retainNow(PDF_KEY,
                RecordRetention.EINVOICE);

        assertThatThrownBy(() -> steps.issued(invoice.id(), invoice.transactionId(), ISSUED, DOCUMENTS))
                .isInstanceOf(StorageException.class);

        assertThat(invoice.status()).as("still waiting: reconciled again").isEqualTo(EInvoiceStatus.SUBMITTING);
        assertThat(invoice.snapshot().invoiceNumber()).isNull();
        verify(invoices, never()).save(any());
    }

    @Test
    @DisplayName("an answer for another transaction, or for an invoice that moved on, stores nothing")
    void anAnswerNobodyWaitsFor() {
        steps.issued(invoice.id(), invoice.id() + "-9", ISSUED, DOCUMENTS);
        EInvoice issued = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        when(invoices.findByIdForUpdate(issued.id())).thenReturn(Optional.of(issued));
        when(invoices.findById(issued.id())).thenReturn(Optional.of(issued));
        steps.issued(issued.id(), issued.transactionId(), ISSUED, DOCUMENTS);

        verify(files, never()).store(any(), any(FileUpload.class));
        verify(files, never()).retainNow(any(), any());
        verify(invoices, never()).save(any());
    }

    @Test
    @DisplayName("an invoice the provider no longer knows is announced to the accountants, once (UNKNOWN_TO_PROVIDER)")
    void anInvoiceUnknownToTheProviderIsAnnounced() {
        assertThat(steps.flagUnknown(invoice.id(), invoice.transactionId())).isTrue();
        assertThat(steps.flagUnknown(invoice.id(), invoice.transactionId())).as("already marked").isFalse();

        ArgumentCaptor<EInvoiceNeedsAttention> told = ArgumentCaptor.forClass(EInvoiceNeedsAttention.class);
        verify(events, times(1)).publishEvent(told.capture());
        assertThat(told.getValue().einvoiceId()).isEqualTo(invoice.id());
        assertThat(told.getValue().cause()).isEqualTo(EInvoiceNeedsAttention.Cause.UNKNOWN_TO_PROVIDER);
        assertThat(told.getValue().rejectionCode()).isNull();
    }

    @Test
    @DisplayName("taking an unknown invoice back to send it again is not announced as a refusal; a real refusal is")
    void aResubmissionIsNotARefusal() {
        steps.rejected(invoice.id(), invoice.transactionId(), EInvoice.UNKNOWN_TO_PROVIDER_CODE, "no such transaction");

        assertThat(invoice.status()).isEqualTo(EInvoiceStatus.REJECTED);
        verify(events, never()).publishEvent(any(Object.class));

        EInvoice refused = EInvoiceFixtures.in(EInvoiceStatus.SUBMITTING);
        when(invoices.findByIdForUpdate(refused.id())).thenReturn(Optional.of(refused));
        steps.rejected(refused.id(), refused.transactionId(), "ERR_TAX_CODE", "buyer tax code unknown");

        ArgumentCaptor<EInvoiceNeedsAttention> told = ArgumentCaptor.forClass(EInvoiceNeedsAttention.class);
        verify(events).publishEvent(told.capture());
        assertThat(told.getValue().cause()).isEqualTo(EInvoiceNeedsAttention.Cause.REJECTED);
        assertThat(told.getValue().rejectionCode()).isEqualTo("ERR_TAX_CODE");
    }

    @Test
    @DisplayName("a correction is not sent to a provider that would issue it as an original: 409, and it stays a draft")
    void aCorrectionIsNotSentToAProviderThatCannotCorrectIt() {
        EInvoice correction = EInvoiceFixtures.agreed(EInvoiceFixtures.adjustment(
                EInvoiceFixtures.in(EInvoiceStatus.ISSUED), CorrectionBasis.ERROR, -1));
        when(invoices.findByIdForUpdate(correction.id())).thenReturn(Optional.of(correction));
        when(settings.active()).thenReturn(new InvoiceSettings.Provider("VIETTEL_SINVOICE", "SANDBOX",
                "https://sinvoice.example", "user", true, true, 1));
        EInvoiceProvider viettel = mock(EInvoiceProvider.class);
        when(viettel.issuesCorrections()).thenReturn(false);
        when(providers.require("VIETTEL_SINVOICE")).thenReturn(viettel);

        assertThatThrownBy(() -> steps.submit(correction.id(), null, null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.EINVOICE_PROVIDER_CANNOT_CORRECT);
                    assertThat(refused.errorCode().httpStatus()).isEqualTo(409);
                });

        assertThat(correction.status()).isEqualTo(EInvoiceStatus.DRAFT);
        verify(invoices, never()).save(any());
        verify(settings, never()).seriesInUse(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("E0 M6: a draft is not sent while one dated earlier this year is still unissued - numbers follow dates")
    void anEarlierUnissuedDraftGoesFirst() {
        EInvoice draft = EInvoiceFixtures.draft();
        when(invoices.findByIdForUpdate(draft.id())).thenReturn(Optional.of(draft));
        when(settings.active()).thenReturn(new InvoiceSettings.Provider("SANDBOX", "SANDBOX",
                "https://sandbox.example", "user", true, true, 1));
        when(providers.require("SANDBOX")).thenReturn(mock(EInvoiceProvider.class));
        when(settings.seriesInUse("SANDBOX", draft.issueDate().getYear()))
                .thenReturn(Optional.of(EInvoiceFixtures.SERIES));
        when(invoices.findEarlierUnissued(draft.id(), draft.issueDate())).thenReturn(Optional.of("EINV-20261110-0003"));

        assertThatThrownBy(() -> steps.submit(draft.id(), null, null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.EINVOICE_OLDER_DRAFT_PENDING);
                    assertThat(refused.errorCode().httpStatus()).isEqualTo(409);
                });

        assertThat(draft.status()).isEqualTo(EInvoiceStatus.DRAFT);
        verify(invoices, never()).save(any());
    }
}
