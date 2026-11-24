package com.stockflow.invoicing.internal.service;

import com.stockflow.contracts.EInvoiceIssueDue;
import com.stockflow.contracts.EInvoiceNeedsAttention;
import com.stockflow.contracts.EInvoiceProviderAccountAlert;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import com.stockflow.invoicing.internal.domain.InvoiceSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 BE-54 (quota gate), BE-08 (overdue result: V3, every SUBMITTING state has an exit), BE-57 (the deadline
 * warning beside the version): the jobs and their queues.
 */
class EInvoiceJobRulesTest {

    private final EInvoiceRepository invoices = mock(EInvoiceRepository.class);
    private final InvoiceSettings settings = mock(InvoiceSettings.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private EInvoiceWorkQueues queues(Instant now) {
        return new EInvoiceWorkQueues(invoices, settings, new InvoicingProperties(Duration.ofDays(1), null), events,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("BE-57: the deadline warning is written beside the invoice's version - the accountant's open screen "
            + "does not go stale - and told once")
    void theDeadlineWarningLeavesTheVersionAlone() {
        EInvoice invoice = EInvoiceFixtures.in(EInvoiceStatus.SUBMITTING);
        when(invoices.findById(invoice.id())).thenReturn(Optional.of(invoice));
        when(invoices.markDueWarned(eq(invoice.id()), any())).thenReturn(true, false);

        Optional<EInvoiceIssueDue> first = queues(EInvoiceFixtures.NOW).warnOfDeadline(invoice.id());
        Optional<EInvoiceIssueDue> second = queues(EInvoiceFixtures.NOW).warnOfDeadline(invoice.id());

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        verify(invoices, never()).save(any());
        verify(invoices, never()).findByIdForUpdate(any());
        verify(events, times(1)).publishEvent(any(EInvoiceIssueDue.class));
    }

    @Test
    @DisplayName("BE-08 (V3): past four hours at the provider, the accountants are told its result is overdue and may "
            + "be recorded from the portal - once per submission, and not before")
    void anOverdueResultIsAnnouncedOnce() {
        EInvoice invoice = EInvoiceFixtures.in(EInvoiceStatus.SUBMITTING);
        when(invoices.findById(invoice.id())).thenReturn(Optional.of(invoice));
        when(invoices.markResultOverdueAnnounced(eq(invoice.id()), eq(invoice.transactionId()), any()))
                .thenReturn(true, false);

        assertThat(queues(EInvoiceFixtures.NOW.plus(Duration.ofHours(3))).announceResultOverdue(invoice.id()))
                .as("not yet").isEmpty();
        Optional<EInvoiceNeedsAttention> told = queues(EInvoiceFixtures.NOW.plus(Duration.ofHours(5)))
                .announceResultOverdue(invoice.id());
        assertThat(told).hasValueSatisfying(event -> assertThat(event.cause())
                .isEqualTo(EInvoiceNeedsAttention.Cause.PROVIDER_RESULT_OVERDUE));
        assertThat(queues(EInvoiceFixtures.NOW.plus(Duration.ofHours(6))).announceResultOverdue(invoice.id()))
                .as("once").isEmpty();
    }

    @Test
    @DisplayName("BE-54 (Q-22 BR-09): the quota is asked of the provider at most every five minutes; at 0 the "
            + "automatic issuing sends nothing - the drafts stay drafts - and the accountants are told at most hourly")
    void anExhaustedQuotaHoldsTheAutomaticIssuingBack() {
        EInvoiceProviders providers = mock(EInvoiceProviders.class);
        EInvoiceProvider sepay = mock(EInvoiceProvider.class);
        when(providers.find("SEPAY_EINVOICE")).thenReturn(Optional.of(sepay));
        when(sepay.remainingQuota()).thenReturn(OptionalLong.of(0));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(EInvoiceFixtures.NOW);
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        EInvoiceQuotaGate gate = new EInvoiceQuotaGate(providers, events, clock, transactions);

        EInvoiceWorkQueues queues = mock(EInvoiceWorkQueues.class);
        EInvoiceIssuance issuance = mock(EInvoiceIssuance.class);
        EInvoiceDrafts drafts = mock(EInvoiceDrafts.class);
        UUID waiting = UUID.randomUUID();
        when(queues.autoIssuable(anyInt())).thenReturn(List.of(waiting));
        when(queues.activeProvider()).thenReturn("SEPAY_EINVOICE");
        EInvoiceJobs jobs = new EInvoiceJobs(queues, issuance, mock(EInvoiceQuotaWatch.class), drafts, gate);

        assertThat(jobs.issueAutomatically()).isZero();
        assertThat(jobs.issueAutomatically()).isZero();

        verify(issuance, never()).issue(any(), any(), any());
        verify(drafts, never()).recheckForAutomaticIssue(any());
        verify(sepay, times(1)).remainingQuota();
        ArgumentCaptor<EInvoiceProviderAccountAlert> told = ArgumentCaptor.forClass(EInvoiceProviderAccountAlert.class);
        verify(events, times(1)).publishEvent(told.capture());
        assertThat(told.getValue().problem()).isEqualTo(EInvoiceProviderAccountAlert.Problem.QUOTA_EXHAUSTED);

        // Topped up: once the five minutes are over, the drafts go out by themselves.
        when(sepay.remainingQuota()).thenReturn(OptionalLong.of(500));
        when(clock.instant()).thenReturn(EInvoiceFixtures.NOW.plus(EInvoiceQuotaGate.FRESH));
        when(drafts.recheckForAutomaticIssue(waiting)).thenReturn(true);
        assertThat(jobs.issueAutomatically()).isEqualTo(1);
        verify(issuance).issue(waiting, null, null);
    }

    @Test
    @DisplayName("audit r2 M-2: a draft held back for its warnings is announced at once, AUTO_ISSUE_HELD with the "
            + "warnings as the reason - once per reason, and never for an invoice that is no longer a draft")
    void aHeldDraftIsAnnouncedOncePerReason() {
        EInvoice draft = EInvoiceFixtures.draft();
        when(invoices.findById(draft.id())).thenReturn(Optional.of(draft));
        when(invoices.markHeldForWarnings(eq(draft.id()), any()))
                .thenReturn(Optional.of("TAX_CLASS_UNKNOWN"), Optional.empty());

        Optional<EInvoiceNeedsAttention> told = queues(EInvoiceFixtures.NOW).announceHeld(draft.id(), null);
        assertThat(told).hasValueSatisfying(event -> {
            assertThat(event.cause()).isEqualTo(EInvoiceNeedsAttention.Cause.AUTO_ISSUE_HELD);
            assertThat(event.rejectionCode()).isEqualTo("TAX_CLASS_UNKNOWN");
            assertThat(event.issueDueAt()).isEqualTo(draft.snapshot().issueDueAt());
            assertThat(event.submittedAt()).isNull();
        });
        assertThat(queues(EInvoiceFixtures.NOW).announceHeld(draft.id(), null)).as("once").isEmpty();

        when(invoices.markHeldAnnounced(draft.id(), "EINVOICE_SERIES_NOT_CONFIGURED", EInvoiceFixtures.NOW))
                .thenReturn(true, false);
        assertThat(queues(EInvoiceFixtures.NOW).announceHeld(draft.id(), "EINVOICE_SERIES_NOT_CONFIGURED"))
                .as("a new reason is told").isPresent();
        assertThat(queues(EInvoiceFixtures.NOW).announceHeld(draft.id(), "EINVOICE_SERIES_NOT_CONFIGURED"))
                .isEmpty();

        EInvoice sent = EInvoiceFixtures.in(EInvoiceStatus.SUBMITTING);
        when(invoices.findById(sent.id())).thenReturn(Optional.of(sent));
        assertThat(queues(EInvoiceFixtures.NOW).announceHeld(sent.id(), "EINVOICE_SERIES_NOT_CONFIGURED")).isEmpty();
        verify(invoices, never()).markHeldAnnounced(eq(sent.id()), any(), any());
        verify(events, times(2)).publishEvent(any(EInvoiceNeedsAttention.class));
    }

    @Test
    @DisplayName("audit r2 M-2: the automatic issuing refused for a draft's own reason tells the accountant at once; a "
            + "passing refusal (a concurrent change, the provider down) or a used-up plan is not told per draft")
    void anAutomaticIssueRefusedIsAnnounced() {
        EInvoiceWorkQueues queues = mock(EInvoiceWorkQueues.class);
        EInvoiceIssuance issuance = mock(EInvoiceIssuance.class);
        EInvoiceDrafts drafts = mock(EInvoiceDrafts.class);
        EInvoiceQuotaGate gate = mock(EInvoiceQuotaGate.class);
        UUID noSeries = UUID.randomUUID();
        UUID raced = UUID.randomUUID();
        UUID held = UUID.randomUUID();
        when(queues.autoIssuable(anyInt())).thenReturn(List.of(noSeries, raced));
        when(queues.activeProvider()).thenReturn("SANDBOX");
        when(drafts.recheckForAutomaticIssue(any())).thenReturn(true);
        when(issuance.issue(noSeries, null, null)).thenThrow(new com.stockflow.common.error.BusinessException(
                com.stockflow.common.error.ErrorCode.EINVOICE_SERIES_NOT_CONFIGURED, "no series"));
        when(issuance.issue(raced, null, null)).thenThrow(new com.stockflow.common.error.BusinessException(
                com.stockflow.common.error.ErrorCode.OPTIMISTIC_LOCK, "raced"));
        when(queues.heldUnannounced(anyInt())).thenReturn(List.of(held));
        when(queues.announceHeld(any(), any())).thenReturn(Optional.of(mock(EInvoiceNeedsAttention.class)));
        EInvoiceJobs jobs = new EInvoiceJobs(queues, issuance, mock(EInvoiceQuotaWatch.class), drafts, gate);

        jobs.reconcileAndIssue();

        verify(queues).announceHeld(noSeries, "EINVOICE_SERIES_NOT_CONFIGURED");
        verify(queues, never()).announceHeld(eq(raced), any());
        verify(queues).announceHeld(held, null);
        assertThat(EInvoiceJobs.worthTelling(com.stockflow.common.error.ErrorCode.EINVOICE_QUOTA_EXHAUSTED)).isFalse();
        assertThat(EInvoiceJobs.worthTelling(com.stockflow.common.error.ErrorCode.EINVOICE_PROVIDER_UNAVAILABLE))
                .isFalse();
        assertThat(EInvoiceJobs.worthTelling(com.stockflow.common.error.ErrorCode.EINVOICE_OLDER_DRAFT_PENDING))
                .isTrue();
    }

    @Test
    @DisplayName("BE-54: a provider that does not tell its quota, or does not answer, holds nothing back")
    void anUnknownQuotaHoldsNothingBack() {
        EInvoiceProviders providers = mock(EInvoiceProviders.class);
        EInvoiceProvider sandbox = mock(EInvoiceProvider.class);
        when(providers.find("SANDBOX")).thenReturn(Optional.of(sandbox));
        when(sandbox.remainingQuota()).thenReturn(OptionalLong.empty());
        EInvoiceQuotaGate gate = new EInvoiceQuotaGate(providers, events, Clock.fixed(EInvoiceFixtures.NOW,
                ZoneOffset.UTC), mock(PlatformTransactionManager.class));
        assertThat(gate.exhausted("SANDBOX")).isFalse();
        when(sandbox.remainingQuota()).thenThrow(new EInvoiceProvider.ProviderUnavailableException("down"));
        assertThat(gate.exhausted("SANDBOX")).isFalse();
        assertThat(gate.exhausted("UNKNOWN")).isFalse();
    }
    @Test
    @DisplayName("BE-54: a quota read a moment ago counts each invoice sent, and a refusal for a used-up plan holds "
            + "everything back at once - a batch never sends invoices the plan does not hold")
    void theGateCountsWhatIsSentAndBelievesARefusal() {
        EInvoiceProviders providers = mock(EInvoiceProviders.class);
        EInvoiceProvider sepay = mock(EInvoiceProvider.class);
        when(providers.find("SEPAY_EINVOICE")).thenReturn(Optional.of(sepay));
        when(sepay.remainingQuota()).thenReturn(OptionalLong.of(2));
        EInvoiceQuotaGate gate = new EInvoiceQuotaGate(providers, events, Clock.fixed(EInvoiceFixtures.NOW,
                ZoneOffset.UTC), mock(PlatformTransactionManager.class));

        assertThat(gate.exhausted("SEPAY_EINVOICE")).isFalse();
        gate.sent("SEPAY_EINVOICE");
        assertThat(gate.exhausted("SEPAY_EINVOICE")).isFalse();
        gate.sent("SEPAY_EINVOICE");
        assertThat(gate.exhausted("SEPAY_EINVOICE")).isTrue();
        verify(sepay, times(1)).remainingQuota();

        EInvoiceQuotaGate other = new EInvoiceQuotaGate(providers, events, Clock.fixed(EInvoiceFixtures.NOW,
                ZoneOffset.UTC), mock(PlatformTransactionManager.class));
        when(sepay.remainingQuota()).thenReturn(OptionalLong.of(500));
        assertThat(other.exhausted("SEPAY_EINVOICE")).isFalse();
        other.on(new EInvoiceProviderAccountAlert("SEPAY_EINVOICE", EInvoiceProviderAccountAlert.Problem.QUOTA_EXHAUSTED,
                "SEPAY_QUOTA_EXHAUSTED", null, null, EInvoiceFixtures.NOW));
        assertThat(other.exhausted("SEPAY_EINVOICE")).isTrue();
        other.on(new EInvoiceProviderAccountAlert("SEPAY_EINVOICE", EInvoiceProviderAccountAlert.Problem.BILLING_UNPAID,
                "X", null, null, EInvoiceFixtures.NOW));
        assertThat(other.exhausted("SEPAY_EINVOICE")).isTrue();
    }
}
