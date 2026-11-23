package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.RecordRetention;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.contracts.EInvoiceNeedsAttention;
import com.stockflow.contracts.EInvoiceProviderAccountAlert;
import com.stockflow.invoicing.internal.domain.CorrectionBasis;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceSending;
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
import java.time.LocalDate;
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
    private final EInvoiceSending sending = mock(EInvoiceSending.class);
    private final InvoiceSettings settings = mock(InvoiceSettings.class);
    private final EInvoiceProviders providers = mock(EInvoiceProviders.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final EInvoiceIssuanceSteps steps = new EInvoiceIssuanceSteps(invoices, sending, settings, providers, files, events,
            Clock.fixed(EInvoiceFixtures.NOW, ZoneOffset.UTC), new InvoicingProperties(java.time.Duration.ofDays(1), null));
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
        EInvoice original = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        EInvoice correction = EInvoiceFixtures.agreed(EInvoiceFixtures.adjustment(original, CorrectionBasis.ERROR,
                -1));
        when(invoices.findByIdForUpdate(correction.id())).thenReturn(Optional.of(correction));
        when(invoices.findById(original.id())).thenReturn(Optional.of(original));
        when(settings.active()).thenReturn(new InvoiceSettings.Provider("SANDBOX", "SANDBOX",
                "https://sandbox.example", "user", true, true, 1));
        EInvoiceProvider sandbox = mock(EInvoiceProvider.class);
        when(sandbox.issuesCorrections()).thenReturn(false);
        when(providers.require("SANDBOX")).thenReturn(sandbox);

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
    @DisplayName("HĐ-2: a draft is not sent while one of its series dated earlier is at the provider; the check runs "
            + "under the series' lock")
    void anEarlierInvoiceOfTheSeriesAtTheProviderGoesFirst() {
        EInvoice draft = aDraftToSendAtTheSandbox(EInvoiceFixtures.draft());
        when(invoices.findEarlierSubmitting(draft.id(), EInvoiceFixtures.SERIES, draft.issueDate()))
                .thenReturn(Optional.of("EINV-20261110-0003"));

        assertThatThrownBy(() -> steps.submit(draft.id(), null, null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.EINVOICE_OLDER_DRAFT_PENDING);
                    assertThat(refused.errorCode().httpStatus()).isEqualTo(409);
                    assertThat(refused.getMessage()).contains("EINV-20261110-0003", "1C26TSB");
                });

        assertThat(draft.status()).isEqualTo(EInvoiceStatus.DRAFT);
        verify(invoices, never()).save(any());
        InOrder order = inOrder(invoices);
        order.verify(invoices).findByIdForUpdate(draft.id());
        order.verify(invoices).lockSeries(EInvoiceFixtures.SERIES);
        order.verify(invoices).findEarlierSubmitting(draft.id(), EInvoiceFixtures.SERIES, draft.issueDate());
    }

    @Test
    @DisplayName("audit r2 M-4 (HĐ-2): under a provider that takes only today's date (SePay), an invoice stuck at the "
            + "provider since an earlier day keeps nothing back - the provider dates and numbers it when it issues it")
    void anEarlierInvoiceStuckAtAProviderThatDatesOnlyTodayKeepsNothingBack() {
        EInvoice draft = aDraftToSendAtTheSandbox(EInvoiceFixtures.draft());
        EInvoiceProvider sepay = mock(EInvoiceProvider.class);
        when(sepay.datesOnlyToday()).thenReturn(true);
        when(providers.require("SANDBOX")).thenReturn(sepay);
        LocalDate today = com.stockflow.common.domain.BusinessCalendar.date(EInvoiceFixtures.NOW);
        when(settings.seriesInUse("SANDBOX", today.getYear())).thenReturn(Optional.of(EInvoiceFixtures.SERIES));
        when(invoices.findEarlierSubmitting(any(), any(), any())).thenReturn(Optional.of("EINV-20261110-0003"));

        assertThat(steps.submit(draft.id(), null, null).status()).isEqualTo(EInvoiceStatus.SUBMITTING);

        verify(invoices).lockSeries(EInvoiceFixtures.SERIES);
        verify(invoices, never()).findEarlierSubmitting(any(), any(), any());
    }

    @Test
    @DisplayName("HĐ-2: with nothing of its series at the provider a draft is sent, whatever drafts or rejected "
            + "invoices are dated before it - they are not asked about")
    void anEarlierDraftOrRejectedInvoiceKeepsNothingBack() {
        EInvoice draft = aDraftToSendAtTheSandbox(EInvoiceFixtures.draft());
        when(invoices.findEarlierSubmitting(any(), any(), any())).thenReturn(Optional.empty());

        assertThat(steps.submit(draft.id(), null, null).status()).isEqualTo(EInvoiceStatus.SUBMITTING);

        verify(invoices).lockSeries(EInvoiceFixtures.SERIES);
        verify(invoices, never()).findEarlierUnissued(any(), any(), any());
    }

    @Test
    @DisplayName("HĐ-7: a draft with a line at 0% - tax class EXEMPT - is not sent")
    void aLineAtZeroPercentIsNotSent() {
        EInvoice draft = aDraftToSendAtTheSandbox(EInvoiceFixtures.draftAtRate(java.math.BigDecimal.ZERO));

        assertThatThrownBy(() -> steps.submit(draft.id(), null, null))
                .isInstanceOfSatisfying(BusinessException.class, refused ->
                        assertThat(refused.errorCode()).isEqualTo(ErrorCode.EINVOICE_TAX_EXEMPT_NOT_SUPPORTED));

        assertThat(draft.status()).isEqualTo(EInvoiceStatus.DRAFT);
        verify(invoices, never()).save(any());
    }

    @Test
    @DisplayName("HĐ-5: a correction goes through the provider that issued the original, not the one in use now")
    void aCorrectionGoesThroughTheOriginalsProvider() {
        EInvoice correction = aCorrectionOfASandboxInvoiceWhileSepayIsInUse();
        when(providers.usable("SANDBOX")).thenReturn(true);
        EInvoiceProvider sandbox = mock(EInvoiceProvider.class);
        when(sandbox.issuesCorrections()).thenReturn(true);
        when(providers.require("SANDBOX")).thenReturn(sandbox);
        when(settings.seriesInUse("SANDBOX", correction.issueDate().getYear()))
                .thenReturn(Optional.of(EInvoiceFixtures.SERIES));

        EInvoice.Snapshot sent = steps.submit(correction.id(), null, null);

        assertThat(sent.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        assertThat(sent.provider()).isEqualTo("SANDBOX");
        assertThat(sent.symbol()).isEqualTo(EInvoiceFixtures.SERIES.symbol());
        verify(providers, never()).require("SEPAY_EINVOICE");
        verify(settings, never()).seriesInUse(eq("SEPAY_EINVOICE"), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("HĐ-5: the original's provider no longer usable - 409 EINVOICE_ORIGINAL_PROVIDER_UNAVAILABLE, never "
            + "sent to the provider in use")
    void aCorrectionIsNeverSentToAnotherProvider() {
        EInvoice correction = aCorrectionOfASandboxInvoiceWhileSepayIsInUse();
        when(providers.usable("SANDBOX")).thenReturn(false);

        assertThatThrownBy(() -> steps.submit(correction.id(), null, null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.EINVOICE_ORIGINAL_PROVIDER_UNAVAILABLE);
                    assertThat(refused.errorCode().httpStatus()).isEqualTo(409);
                });

        assertThat(correction.status()).isEqualTo(EInvoiceStatus.DRAFT);
        verify(providers, never()).require(any());
        verify(invoices, never()).save(any());
    }

    @Test
    @DisplayName("HĐ-5: no series in use at the original's provider for the year - refused the same way")
    void aCorrectionNeedsASeriesAtTheOriginalsProvider() {
        EInvoice correction = aCorrectionOfASandboxInvoiceWhileSepayIsInUse();
        when(providers.usable("SANDBOX")).thenReturn(true);
        EInvoiceProvider sandbox = mock(EInvoiceProvider.class);
        when(sandbox.issuesCorrections()).thenReturn(true);
        when(providers.require("SANDBOX")).thenReturn(sandbox);
        when(settings.seriesInUse("SANDBOX", correction.issueDate().getYear())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> steps.submit(correction.id(), null, null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.EINVOICE_ORIGINAL_PROVIDER_UNAVAILABLE));
        verify(invoices, never()).save(any());
    }

    @Test
    @DisplayName("HĐ-1: a refusal of the provider's account is announced once per cause, not as the invoice's refusal")
    void aRefusalOfTheAccountIsAnnouncedAsTheAccounts() {
        EInvoiceProvider sandbox = mock(EInvoiceProvider.class);
        when(providers.find(invoice.provider())).thenReturn(Optional.of(sandbox));
        when(sandbox.accountProblem("SEPAY_QUOTA_EXHAUSTED"))
                .thenReturn(Optional.of(EInvoiceProvider.AccountProblem.QUOTA_EXHAUSTED));

        steps.rejected(invoice.id(), invoice.transactionId(), "SEPAY_QUOTA_EXHAUSTED", "QUOTA_HAS_BEEN_USERD_UP: het");

        assertThat(invoice.status()).as("refused: nothing was created, it is issued again once the plan is topped up")
                .isEqualTo(EInvoiceStatus.REJECTED);
        ArgumentCaptor<Object> told = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(told.capture());
        assertThat(told.getValue()).isInstanceOfSatisfying(EInvoiceProviderAccountAlert.class, alert -> {
            assertThat(alert.provider()).isEqualTo(invoice.provider());
            assertThat(alert.problem()).isEqualTo(EInvoiceProviderAccountAlert.Problem.QUOTA_EXHAUSTED);
            assertThat(alert.rejectionCode()).isEqualTo("SEPAY_QUOTA_EXHAUSTED");
        });
    }

    @Test
    @DisplayName("HĐ-1: an invoice the accountant took back from the provider is not announced as a refusal")
    void aWithdrawalIsNotARefusal() {
        steps.rejected(invoice.id(), invoice.transactionId(), EInvoice.WITHDRAWN_CODE, "SePay confirmed: no record");

        assertThat(invoice.status()).isEqualTo(EInvoiceStatus.REJECTED);
        assertThat(invoice.snapshot().rejectionMessage()).isEqualTo("SePay confirmed: no record");
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("HĐ-8: an XML whose seller's signature does not hold is issued but flagged, and the accountants are "
            + "told instead of the buyer")
    void anInvoiceWithASignatureThatDoesNotHoldIsNotSentToTheBuyer() {
        EInvoiceProvider signing = mock(EInvoiceProvider.class);
        when(signing.signsDocuments()).thenReturn(true);
        when(providers.find(invoice.provider())).thenReturn(Optional.of(signing));

        steps.issued(invoice.id(), invoice.transactionId(), ISSUED, DOCUMENTS);

        assertThat(invoice.status()).as("the provider issued it: it exists in law").isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(invoice.snapshot().warnings()).containsExactly(EInvoice.Warning.SIGNATURE_INVALID);
        ArgumentCaptor<Object> told = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(told.capture());
        assertThat(told.getValue()).isInstanceOfSatisfying(EInvoiceNeedsAttention.class, attention ->
                assertThat(attention.cause()).isEqualTo(EInvoiceNeedsAttention.Cause.SIGNATURE_INVALID));
    }

    @Test
    @DisplayName("HĐ-8: a provider whose documents are not signed (the sandbox) is not held to a signature")
    void anUnsignedProvidersInvoiceGoesToTheBuyer() {
        EInvoiceProvider sandbox = mock(EInvoiceProvider.class);
        when(sandbox.signsDocuments()).thenReturn(false);
        when(providers.find(invoice.provider())).thenReturn(Optional.of(sandbox));

        steps.issued(invoice.id(), invoice.transactionId(), ISSUED, DOCUMENTS);

        assertThat(invoice.snapshot().warnings()).isEmpty();
        ArgumentCaptor<Object> told = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(told.capture());
        assertThat(told.getValue()).isInstanceOf(EInvoiceIssued.class);
    }

    /** An adjustment of an invoice issued at the sandbox, drawn up after SePay was put in use. */
    private EInvoice aCorrectionOfASandboxInvoiceWhileSepayIsInUse() {
        EInvoice original = EInvoiceFixtures.in(EInvoiceStatus.ISSUED);
        EInvoice correction = EInvoiceFixtures.adjustment(original, CorrectionBasis.RETURN, -1);
        when(invoices.findByIdForUpdate(correction.id())).thenReturn(Optional.of(correction));
        when(invoices.findById(original.id())).thenReturn(Optional.of(original));
        when(invoices.findEarlierSubmitting(any(), any(), any())).thenReturn(Optional.empty());
        when(settings.active()).thenReturn(new InvoiceSettings.Provider("SEPAY_EINVOICE", "SANDBOX", null, null,
                false, false, 1));
        return correction;
    }

    private EInvoice aDraftToSendAtTheSandbox(EInvoice draft) {
        when(invoices.findByIdForUpdate(draft.id())).thenReturn(Optional.of(draft));
        when(settings.active()).thenReturn(new InvoiceSettings.Provider("SANDBOX", "SANDBOX",
                "https://sandbox.example", "user", true, true, 1));
        when(providers.require("SANDBOX")).thenReturn(mock(EInvoiceProvider.class));
        when(settings.seriesInUse("SANDBOX", draft.issueDate().getYear()))
                .thenReturn(Optional.of(EInvoiceFixtures.SERIES));
        return draft;
    }
}
