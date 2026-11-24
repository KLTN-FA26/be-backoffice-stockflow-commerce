package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.contracts.EInvoiceIssued;
import com.stockflow.contracts.EInvoiceNeedsAttention;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceSending;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.InvoiceSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.AsyncTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 issuing rules at the step that sends and the step that records: an original issued late (Q-10, BE-26), the
 * provider's series (BE-30), the watch of let-go transactions (BE-07), the quota (BE-54) and the manual issue's
 * re-check (BE-25, invariant V8).
 */
class EInvoiceIssuingRulesTest {

    /** Four days after the fixtures' delivery. */
    private static final Instant LATER = EInvoiceFixtures.NOW.plus(Duration.ofDays(4));

    private final EInvoiceRepository invoices = mock(EInvoiceRepository.class);
    private final InvoiceSettings settings = mock(InvoiceSettings.class);
    private final EInvoiceProviders providers = mock(EInvoiceProviders.class);
    private final FileTransfers files = mock(FileTransfers.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final EInvoiceProvider sepay = mock(EInvoiceProvider.class);

    private EInvoiceIssuanceSteps steps(Instant now) {
        return new EInvoiceIssuanceSteps(invoices, mock(EInvoiceSending.class), settings, providers, files, events,
                Clock.fixed(now, ZoneOffset.UTC), new InvoicingProperties(Duration.ofDays(1), null));
    }

    private EInvoice aSepayDraft() {
        EInvoice draft = EInvoiceFixtures.draft();
        when(invoices.findByIdForUpdate(draft.id())).thenReturn(Optional.of(draft));
        when(invoices.findById(draft.id())).thenReturn(Optional.of(draft));
        when(invoices.save(any())).thenAnswer(call -> call.getArgument(0));
        when(settings.active()).thenReturn(new InvoiceSettings.Provider("SANDBOX", "SANDBOX", null, null, false,
                true, 1));
        when(providers.require("SANDBOX")).thenReturn(sepay);
        when(sepay.datesOnlyToday()).thenReturn(true);
        when(settings.seriesInUse(eq("SANDBOX"), anyInt())).thenReturn(Optional.of(EInvoiceFixtures.SERIES));
        return draft;
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }

    @Test
    @DisplayName("Q-10: the accountant issuing an original after its delivery day says why "
            + "(EINVOICE_LATE_ISSUE_REASON_REQUIRED); with a reason it goes out dated today, flagged ISSUED_LATE, and "
            + "the accountants are told with both days")
    void aLateManualIssueNeedsAReason() {
        EInvoice draft = aSepayDraft();
        EInvoiceIssuanceSteps steps = steps(LATER);

        assertThatThrownBy(() -> steps.submit(draft.id(), null, UUID.randomUUID(), true, null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.EINVOICE_LATE_ISSUE_REASON_REQUIRED));
        assertThat(draft.status()).isEqualTo(EInvoiceStatus.DRAFT);

        EInvoice.Snapshot sent = steps.submit(draft.id(), null, UUID.randomUUID(), true, "SePay was down all day");

        assertThat(sent.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        assertThat(sent.issueDate()).isEqualTo(EInvoiceFixtures.DELIVERED_ON.plusDays(4));
        assertThat(sent.deliveredOn()).isEqualTo(EInvoiceFixtures.DELIVERED_ON);
        assertThat(sent.warnings()).contains(EInvoice.Warning.ISSUED_LATE);
        ArgumentCaptor<EInvoiceNeedsAttention> told = ArgumentCaptor.forClass(EInvoiceNeedsAttention.class);
        verify(events).publishEvent(told.capture());
        assertThat(told.getValue().cause()).isEqualTo(EInvoiceNeedsAttention.Cause.ISSUED_LATE);
        assertThat(told.getValue().deliveredOn()).isEqualTo(EInvoiceFixtures.DELIVERED_ON);
        assertThat(told.getValue().issueDate()).isEqualTo(sent.issueDate());
    }

    @Test
    @DisplayName("Q-10: the automatic issuing is never held back for lateness - every hour waited makes it later")
    void theAutomaticIssueIsNeverHeldBackForLateness() {
        EInvoice draft = aSepayDraft();
        EInvoice.Snapshot sent = steps(LATER).submit(draft.id(), null, null);
        assertThat(sent.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        assertThat(sent.warnings()).contains(EInvoice.Warning.ISSUED_LATE);
    }

    @Test
    @DisplayName("BE-30: the provider numbered it under another series - not recorded ISSUED, told once per "
            + "submission (SERIES_MISMATCH)")
    void anotherSeriesIsNotRecorded() {
        EInvoice invoice = EInvoiceFixtures.in(EInvoiceStatus.SUBMITTING);
        when(invoices.findById(invoice.id())).thenReturn(Optional.of(invoice));
        when(invoices.findByIdForUpdate(invoice.id())).thenReturn(Optional.of(invoice));
        when(invoices.markSeriesMismatchAnnounced(eq(invoice.id()), any(), any())).thenReturn(true, false);
        EInvoiceProvider.Result elsewhere = new EInvoiceProvider.Result(EInvoiceProvider.ResultState.ISSUED, 5, "M5",
                "L5", EInvoiceFixtures.NOW, null, null).numberedUnder("1", "C26TXX");
        EInvoiceProvider.Documents documents = new EInvoiceProvider.Documents(
                "<HDon/>".getBytes(StandardCharsets.UTF_8), "%PDF".getBytes(StandardCharsets.UTF_8));
        EInvoiceIssuanceSteps steps = steps(EInvoiceFixtures.NOW);

        steps.issued(invoice.id(), invoice.transactionId(), elsewhere, documents);
        steps.issued(invoice.id(), invoice.transactionId(), elsewhere, documents);

        assertThat(invoice.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        verify(files, never()).store(any(), any(FileUpload.class));
        ArgumentCaptor<Object> told = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(told.capture());
        assertThat(((EInvoiceNeedsAttention) told.getValue()).cause())
                .isEqualTo(EInvoiceNeedsAttention.Cause.SERIES_MISMATCH);
    }

    @Test
    @DisplayName("BE-07 (V2): a transaction let go - withdrawn, its result unconfirmed, the tax authority's refusal "
            + "recorded, a refusal on the processing report alone - is watched for 30 days; a refusal the look-up "
            + "stood behind is not")
    void letGoTransactionsAreWatched() {
        when(invoices.save(any())).thenAnswer(call -> call.getArgument(0));
        when(providers.find(any())).thenReturn(Optional.of(sepay));
        when(sepay.refusalUnconfirmedByLookup("SEPAY_ISSUANCE_FAILED")).thenReturn(true);
        EInvoiceIssuanceSteps steps = steps(EInvoiceFixtures.NOW);
        for (String code : java.util.List.of(EInvoice.WITHDRAWN_CODE, EInvoice.WITHDRAWN_RESULT_UNCONFIRMED_CODE,
                EInvoice.REFUSED_BY_TAX_AUTHORITY_CODE, "SEPAY_ISSUANCE_FAILED", "INVALID_BUYER_TAX_CODE")) {
            EInvoice invoice = EInvoiceFixtures.in(EInvoiceStatus.SUBMITTING);
            when(invoices.findByIdForUpdate(invoice.id())).thenReturn(Optional.of(invoice));
            steps.rejected(invoice.id(), invoice.transactionId(), code, "words");
            if ("INVALID_BUYER_TAX_CODE".equals(code)) {
                verify(invoices, never()).watchTransaction(eq(invoice.id()), anyString(), anyString(), anyString(),
                        any());
            } else {
                verify(invoices).watchTransaction(invoice.id(), invoice.transactionId(), invoice.provider(), code,
                        EInvoiceFixtures.NOW.plus(Duration.ofDays(30)));
            }
        }
    }

    @Test
    @DisplayName("BE-08 (Q-8): recorded from the portal, an invoice stuck at the provider is issued with the files "
            + "given, PORTAL_RECORD and who recorded it; its XML's signature is checked as the provider's would be - "
            + "an unsigned one is flagged and the accountants told, not mailed to the buyer")
    void aPortalRecordIssuesIt() {
        EInvoice invoice = EInvoiceFixtures.in(EInvoiceStatus.SUBMITTING);
        when(invoices.findById(invoice.id())).thenReturn(Optional.of(invoice));
        when(invoices.findByIdForUpdate(invoice.id())).thenReturn(Optional.of(invoice));
        when(invoices.save(any())).thenAnswer(call -> call.getArgument(0));
        when(files.store(eq(FileCategory.EINVOICE), any(FileUpload.class))).thenAnswer(call -> {
            FileUpload upload = call.getArgument(1);
            return new StoredFile("einvoices/" + upload.originalName(), upload.originalName(), upload.contentType(),
                    upload.sizeBytes(), EInvoiceFixtures.NOW);
        });
        UUID accountant = UUID.randomUUID();

        boolean recorded = steps(EInvoiceFixtures.NOW).issuedFromPortal(invoice.id(), invoice.transactionId(),
                new EInvoiceProvider.Result(EInvoiceProvider.ResultState.ISSUED, 12, "M1-26-ABC", "LOOK",
                        EInvoiceFixtures.NOW, null, null),
                new EInvoiceProvider.Documents("<HDon/>".getBytes(StandardCharsets.UTF_8),
                        "%PDF".getBytes(StandardCharsets.UTF_8)), accountant);

        assertThat(recorded).isTrue();
        EInvoice.Snapshot issued = invoice.snapshot();
        assertThat(issued.status()).isEqualTo(EInvoiceStatus.ISSUED);
        assertThat(issued.issuedVia()).isEqualTo(EInvoice.IssuedVia.PORTAL_RECORD);
        assertThat(issued.portalRecordedBy()).isEqualTo(accountant);
        assertThat(issued.invoiceNumber()).isEqualTo(12);
        assertThat(issued.warnings()).contains(EInvoice.Warning.SIGNATURE_INVALID);
        ArgumentCaptor<EInvoiceNeedsAttention> told = ArgumentCaptor.forClass(EInvoiceNeedsAttention.class);
        verify(events).publishEvent(told.capture());
        assertThat(told.getValue().cause()).isEqualTo(EInvoiceNeedsAttention.Cause.SIGNATURE_INVALID);
        verify(events, never()).publishEvent(any(EInvoiceIssued.class));
        assertThat(steps(EInvoiceFixtures.NOW).issuedFromPortal(invoice.id(), invoice.transactionId(),
                new EInvoiceProvider.Result(EInvoiceProvider.ResultState.ISSUED, 12, "M", "L", EInvoiceFixtures.NOW,
                        null, null), new EInvoiceProvider.Documents(new byte[] {1}, new byte[] {1}), accountant))
                .as("no longer waiting: a second record changes nothing").isFalse();
    }

    @Test
    @DisplayName("BE-54 (Q-22 BR-09): with the provider's plan used up, the accountant's issue is refused and the "
            + "draft stays a draft; nothing is checked or sent")
    void anExhaustedQuotaRefusesTheIssue() {
        EInvoiceIssuanceSteps steps = mock(EInvoiceIssuanceSteps.class);
        EInvoiceDrafts drafts = mock(EInvoiceDrafts.class);
        EInvoiceQuotaGate quota = mock(EInvoiceQuotaGate.class);
        EInvoiceIssuance issuance = new EInvoiceIssuance(steps, providers, mock(ProviderCallRecorder.class),
                new InvoicingProperties(Duration.ofDays(1), null), Clock.fixed(LATER, ZoneOffset.UTC),
                mock(AsyncTaskExecutor.class), drafts, quota, mock(EInvoicePortalReductions.class));
        UUID id = UUID.randomUUID();
        when(steps.providerFor(id)).thenReturn("SEPAY_EINVOICE");
        when(quota.exhausted("SEPAY_EINVOICE")).thenReturn(true);

        assertThatThrownBy(() -> issuance.issue(id, 1L, Set.of(), null, UUID.randomUUID()))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.EINVOICE_QUOTA_EXHAUSTED));
        assertThatThrownBy(() -> issuance.issue(id, 1L, null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.EINVOICE_QUOTA_EXHAUSTED));
        verify(drafts, never()).recheckForIssue(any(), anyLong(), any());
        verify(steps, never()).submit(any(), any(), any());
        verify(steps, never()).submit(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("BE-25 (V8): what the re-check found and the accountant did not acknowledge refuses the issue with "
            + "the list; acknowledged, it is submitted at the version the re-check left it at, with the late reason")
    void theManualIssueAppliesTheAutomaticChecks() {
        EInvoiceIssuanceSteps steps = mock(EInvoiceIssuanceSteps.class);
        EInvoiceDrafts drafts = mock(EInvoiceDrafts.class);
        EInvoiceIssuance issuance = new EInvoiceIssuance(steps, providers, mock(ProviderCallRecorder.class),
                new InvoicingProperties(Duration.ofDays(1), null), Clock.fixed(LATER, ZoneOffset.UTC),
                mock(AsyncTaskExecutor.class), drafts, mock(EInvoiceQuotaGate.class),
                mock(EInvoicePortalReductions.class));
        UUID id = UUID.randomUUID();
        UUID by = UUID.randomUUID();
        when(drafts.recheckForIssue(id, 3L, Set.of())).thenReturn(new EInvoiceDrafts.IssueCheck(
                Set.of("TAX_CLASS_UNKNOWN", EInvoiceDrafts.BUYER_PROFILE_CHANGED), 4L));

        assertThatThrownBy(() -> issuance.issue(id, 3L, Set.of(), null, by))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.EINVOICE_DRAFT_OUT_OF_DATE);
                    assertThat(refused.details().get("warnings")).isEqualTo(
                            java.util.List.of(EInvoiceDrafts.BUYER_PROFILE_CHANGED, "TAX_CLASS_UNKNOWN"));
                });
        verify(steps, never()).submit(any(), any(), any(), anyBoolean(), any());

        Set<String> both = Set.of("TAX_CLASS_UNKNOWN", EInvoiceDrafts.BUYER_PROFILE_CHANGED);
        when(drafts.recheckForIssue(id, 3L, both)).thenReturn(new EInvoiceDrafts.IssueCheck(Set.of(), 4L));
        issuance.issue(id, 3L, both, "  giao toi muon  ", by);
        verify(steps).submit(id, 4L, by, true, "giao toi muon");
    }
    @Test
    @DisplayName("BE-07 (V1): once the watch found an earlier transaction issued after all, no further attempt is sent "
            + "- the delivery has its invoice in law under the old one")
    void nothingIsSentAgainOnceAnOldTransactionWasFoundIssued() {
        EInvoice draft = aSepayDraft();
        when(invoices.watchFound(draft.id())).thenReturn(true);

        assertThatThrownBy(() -> steps(EInvoiceFixtures.NOW).submit(draft.id(), null, UUID.randomUUID(), true, null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.EINVOICE_RESUBMISSION_REFUSED));
        assertThat(draft.status()).isEqualTo(EInvoiceStatus.DRAFT);
    }
}
