package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ProviderUnavailableException;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Result;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ResultState;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Submission;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.SubmissionOutcome;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.task.AsyncTaskExecutor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When an invoice the provider does not know is sent again, and when it is not (review WF05-einv: H1, H2, M1, M2).
 * The provider and the database steps are stood in for, so the clock can be moved: the provider here has Viettel's
 * limits - seven minutes of quiet after a send, three days of memory.
 */
class EInvoiceIssuanceResendTest {

    private static final Instant SUBMITTED = EInvoiceFixtures.NOW;
    private static final EInvoiceProvider.Resubmission VIETTELS = new EInvoiceProvider.Resubmission(
            Duration.ofMinutes(7), Duration.ofDays(3));

    private final EInvoiceIssuanceSteps steps = mock(EInvoiceIssuanceSteps.class);
    private final EInvoiceProviders providers = mock(EInvoiceProviders.class);
    private final ProviderCallRecorder calls = mock(ProviderCallRecorder.class);
    private final EInvoiceProvider provider = mock(EInvoiceProvider.class);
    private final Clock clock = mock(Clock.class);
    private final EInvoiceIssuance issuance = new EInvoiceIssuance(steps, providers, calls,
            new InvoicingProperties(Duration.ofDays(1), null), clock, mock(AsyncTaskExecutor.class),
            mock(EInvoiceDrafts.class), mock(EInvoiceQuotaGate.class), mock(EInvoicePortalReductions.class));
    private EInvoice.Snapshot invoice;
    private UUID id;
    private String transaction;

    @BeforeEach
    void anInvoiceAtTheProvider() {
        invoice = EInvoiceFixtures.in(EInvoiceStatus.SUBMITTING).snapshot();
        id = invoice.id();
        transaction = invoice.transactionId();
        when(steps.get(id)).thenReturn(invoice);
        when(steps.claim(eq(id), any(UUID.class), any(Duration.class))).thenReturn(true);
        when(steps.aboutToSend(eq(id), eq(transaction), any(UUID.class), any(Duration.class))).thenReturn(true);
        when(providers.require(invoice.provider())).thenReturn(provider);
        when(provider.resubmission()).thenReturn(VIETTELS);
    }

    private void now(Duration sinceSubmitted) {
        when(clock.instant()).thenReturn(SUBMITTED.plus(sinceSubmitted));
    }

    private void lastSent(Duration sinceSubmitted) {
        when(steps.lastSent(id, transaction)).thenReturn(Optional.of(SUBMITTED.plus(sinceSubmitted)));
    }

    @Test
    @DisplayName("review H1: sent again 8 minutes after the first send and not answered; asked 30 seconds later, the "
            + "provider still knows nothing - it is NOT sent a third time, the quiet period runs from the last send")
    void theQuietPeriodRunsFromTheLastSend() {
        lastSent(Duration.ofMinutes(8));
        now(Duration.ofMinutes(8).plusSeconds(30));
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.NOT_FOUND));

        EInvoice.Snapshot waiting = issuance.reconcile(id);

        assertThat(waiting.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        verify(provider, never()).submit(any());
        verify(steps, never()).aboutToSend(any(), any(), any(), any());
        verify(steps, never()).rejected(any(), any(), any(), any());
    }

    @Test
    @DisplayName("review H1: past the quiet period since the last send it is sent again, and the send is recorded "
            + "before the provider is called - a send nobody saw the end of still starts the quiet period")
    void aSendIsRecordedBeforeTheProviderIsCalled() {
        lastSent(Duration.ZERO);
        now(Duration.ofMinutes(7));
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.NOT_FOUND));
        when(provider.submit(any())).thenThrow(new ProviderUnavailableException("no answer"));

        EInvoice.Snapshot waiting = issuance.reconcile(id);

        assertThat(waiting.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        InOrder order = inOrder(steps, provider);
        order.verify(steps).aboutToSend(eq(id), eq(transaction), any(UUID.class), eq(EInvoiceIssuance.PROVIDER_LEASE));
        order.verify(provider).submit(any());
        order.verify(steps).release(eq(id), any(UUID.class));
    }

    @Test
    @DisplayName("review H1: one second short of the quiet period since the last send, nothing is sent")
    void insideTheQuietPeriodNothingIsSent() {
        lastSent(Duration.ZERO);
        now(Duration.ofMinutes(7).minusSeconds(1));
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.NOT_FOUND));

        issuance.reconcile(id);

        verify(provider, never()).submit(any());
    }

    @Test
    @DisplayName("review H2: while somebody else holds the invoice's lease, the provider is neither asked nor sent "
            + "anything, and a lease not taken is not given up")
    void withoutTheLeaseTheProviderIsNotCalled() {
        when(steps.claim(eq(id), any(UUID.class), any(Duration.class))).thenReturn(false);

        EInvoice.Snapshot waiting = issuance.reconcile(id);

        assertThat(waiting.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        verify(provider, never()).query(any());
        verify(provider, never()).submit(any());
        verify(steps, never()).release(any(), any());
        assertThatThrownBy(() -> issuance.resubmitAsNew(id, invoice.version(), null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.EINVOICE_RESUBMISSION_REFUSED));
        verify(provider, never()).query(any());
    }

    @Test
    @DisplayName("review H2: the lease is lost before the send (taken over after it ran out) - nothing is sent")
    void aLeaseLostBeforeTheSendSendsNothing() {
        lastSent(Duration.ZERO);
        now(Duration.ofMinutes(8));
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.NOT_FOUND));
        when(steps.aboutToSend(eq(id), eq(transaction), any(UUID.class), any(Duration.class))).thenReturn(false);

        issuance.reconcile(id);

        verify(provider, never()).submit(any());
    }

    @Test
    @DisplayName("review M1: an answer that does not say whether the transaction is held is not 'never received' - "
            + "nothing is sent again, and 'send as new' is refused (409)")
    void anUnclearAnswerSendsNothing() {
        lastSent(Duration.ZERO);
        now(Duration.ofDays(4));
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.UNKNOWN));

        issuance.reconcile(id);

        verify(provider, never()).submit(any());
        verify(steps, never()).flagUnknown(any(), any());
        assertThatThrownBy(() -> issuance.resubmitAsNew(id, invoice.version(), null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> {
                    assertThat(refused.errorCode()).isEqualTo(ErrorCode.EINVOICE_RESUBMISSION_REFUSED);
                    assertThat(refused.errorCode().httpStatus()).isEqualTo(409);
                });
        verify(steps, never()).rejected(any(), any(), any(), any());
        verify(steps, never()).reopenUnknown(any());
    }

    @Test
    @DisplayName("review M2: a send again that is refused is not recorded as refused while the provider, asked anew, "
            + "does not confirm it holds nothing - the refusal may be of a duplicate of what it holds")
    void aRefusedResendIsNotARejectionUnlessNothingIsHeld() {
        lastSent(Duration.ZERO);
        now(Duration.ofMinutes(8));
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.NOT_FOUND), Result.of(ResultState.PENDING));
        when(provider.submit(any())).thenReturn(new Submission(SubmissionOutcome.REJECTED, "DUPLICATE", "held"));

        EInvoice.Snapshot waiting = issuance.reconcile(id);

        assertThat(waiting.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        verify(provider, times(2)).query(transaction);
        verify(steps, never()).rejected(any(), any(), any(), any());
    }

    @Test
    @DisplayName("review M2: refused, and the provider confirms it holds nothing - recorded as refused; a first send "
            + "refused is recorded without asking again")
    void aRefusalIsRecordedWhenNothingIsHeld() {
        lastSent(Duration.ZERO);
        now(Duration.ofMinutes(8));
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.NOT_FOUND));
        when(provider.submit(any())).thenReturn(new Submission(SubmissionOutcome.REJECTED, "ERR_TAX_CODE", "wrong"));

        issuance.reconcile(id);

        verify(provider, times(2)).query(transaction);
        verify(steps).rejected(id, transaction, "ERR_TAX_CODE", "wrong");

        // Never sent before: nothing can be held under the transaction.
        when(steps.lastSent(id, transaction)).thenReturn(Optional.empty());
        issuance.reconcile(id);

        verify(provider, times(3)).query(transaction);
        verify(steps, times(2)).rejected(id, transaction, "ERR_TAX_CODE", "wrong");
    }

    @Test
    @DisplayName("HĐ-1: the provider's handle on a submission it took in is kept with the call")
    void theProvidersHandleOnASubmissionIsLogged() {
        lastSent(Duration.ZERO);
        now(Duration.ofMinutes(8));
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.NOT_FOUND), Result.of(ResultState.PENDING));
        when(provider.submit(any())).thenReturn(new Submission(SubmissionOutcome.ACCEPTED, null, null,
                "create/084e179d-d95a-11f0-aef4-52c7e9b4f41b"));

        issuance.reconcile(id);

        var logged = org.mockito.ArgumentCaptor.forClass(com.stockflow.invoicing.internal.domain.ProviderCallLog.Call.class);
        verify(calls, times(3)).record(logged.capture());
        assertThat(logged.getAllValues()).filteredOn(call -> call.operation()
                        == com.stockflow.invoicing.internal.domain.ProviderCallLog.Operation.SUBMIT)
                .singleElement().extracting(com.stockflow.invoicing.internal.domain.ProviderCallLog.Call::providerReference)
                .isEqualTo("create/084e179d-d95a-11f0-aef4-52c7e9b4f41b");
    }

    // ---- HĐ-1: taking an invoice stuck at the provider back ------------------------------------------------------

    @Test
    @DisplayName("HĐ-1: last sent more than three hours ago and the provider has no record - taken back as REJECTED "
            + "with the accountant's reason, not announced")
    void aStuckInvoiceTheProviderHasNoRecordOfIsTakenBack() {
        lastSent(Duration.ZERO);
        now(Duration.ofHours(3));
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.NOT_FOUND));

        issuance.withdraw(id, invoice.version(), "  SePay support: no invoice under this reference  ", null);

        verify(steps).rejected(id, transaction, EInvoice.WITHDRAWN_CODE,
                "SePay support: no invoice under this reference");
        verify(provider, never()).submit(any());
        verify(steps).release(eq(id), any(UUID.class));
    }

    @Test
    @DisplayName("HĐ-1 (review): still unknown to the provider an hour after it was submitted - asked, no longer sent "
            + "again by itself, so its last send stops moving")
    void theReconciliationStopsSendingAgainAfterTheAutomaticPeriod() {
        lastSent(Duration.ofMinutes(55));
        now(Duration.ofHours(1));
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.NOT_FOUND));

        EInvoice.Snapshot waiting = issuance.reconcile(id);

        assertThat(waiting.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        verify(provider).query(transaction);
        verify(provider, never()).submit(any());
        verify(steps, never()).aboutToSend(any(), any(), any(), any());
        verify(steps, never()).rejected(any(), any(), any(), any());
    }

    @Test
    @DisplayName("HĐ-1 (review): one second short of the automatic period, a send past the quiet period still goes")
    void insideTheAutomaticPeriodItIsSentAgain() {
        lastSent(Duration.ofMinutes(50));
        now(Duration.ofHours(1).minusSeconds(1));
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.NOT_FOUND));
        when(provider.submit(any())).thenThrow(new ProviderUnavailableException("no answer"));

        issuance.reconcile(id);

        verify(provider).submit(any());
    }

    @Test
    @DisplayName("HĐ-1 (review): sent again until the automatic period ended, then left alone - the accountant can "
            + "take it back three hours after that last send (before, every ten-minute resend kept it out of reach)")
    void aStuckInvoiceBecomesWithdrawable() {
        lastSent(Duration.ofMinutes(55));
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.NOT_FOUND));
        for (Duration at : java.util.List.of(Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(3),
                Duration.ofMinutes(55).plusHours(3).minusSeconds(1))) {
            now(at);
            issuance.reconcile(id);
            assertThatThrownBy(() -> issuance.withdraw(id, invoice.version(), "stuck", null)).as(at.toString())
                    .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                            .isEqualTo(ErrorCode.EINVOICE_WITHDRAWAL_REFUSED));
        }
        verify(provider, never()).submit(any());

        now(Duration.ofMinutes(55).plusHours(3));
        issuance.withdraw(id, invoice.version(), "SePay portal: no invoice under this reference", null);

        verify(steps).rejected(id, transaction, EInvoice.WITHDRAWN_CODE,
                "SePay portal: no invoice under this reference");
    }

    @Test
    @DisplayName("HĐ-1: sent less than three hours ago - refused, the provider is not even asked")
    void aRecentSendIsNotTakenBack() {
        lastSent(Duration.ZERO);
        now(Duration.ofHours(3).minusSeconds(1));

        assertThatThrownBy(() -> issuance.withdraw(id, invoice.version(), "stuck", null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.EINVOICE_WITHDRAWAL_REFUSED));
        verify(provider, never()).query(any());
        verify(steps, never()).rejected(any(), any(), any(), any());
        verify(steps).release(eq(id), any(UUID.class));
    }

    @Test
    @DisplayName("HĐ-1: the provider holds it, is working on it, cannot confirm it (under a day), or does not answer - "
            + "refused")
    void anInvoiceTheProviderMayHoldIsNotTakenBack() {
        lastSent(Duration.ZERO);
        now(Duration.ofHours(4));
        for (ResultState state : java.util.List.of(ResultState.ISSUED, ResultState.PENDING, ResultState.UNKNOWN,
                ResultState.REJECTED)) {
            when(provider.query(transaction)).thenReturn(Result.of(state));
            assertThatThrownBy(() -> issuance.withdraw(id, invoice.version(), "stuck", null)).as(state.name())
                    .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                            .isEqualTo(ErrorCode.EINVOICE_WITHDRAWAL_REFUSED));
        }
        when(provider.query(transaction)).thenThrow(new ProviderUnavailableException("no answer"));
        assertThatThrownBy(() -> issuance.withdraw(id, invoice.version(), "stuck", null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.EINVOICE_PROVIDER_UNAVAILABLE));
        verify(steps, never()).rejected(any(), any(), any(), any());
    }

    @Test
    @DisplayName("HĐ-1: without a reason, a stale version, another holder of the lease - refused, nothing asked")
    void aWithdrawalNeedsAReasonTheVersionAndTheLease() {
        lastSent(Duration.ZERO);
        now(Duration.ofDays(1));
        assertThatThrownBy(() -> issuance.withdraw(id, invoice.version(), " ", null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> issuance.withdraw(id, invoice.version() + 1, "stuck", null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.OPTIMISTIC_LOCK));
        when(steps.claim(eq(id), any(UUID.class), any(Duration.class))).thenReturn(false);
        assertThatThrownBy(() -> issuance.withdraw(id, invoice.version(), "stuck", null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.EINVOICE_WITHDRAWAL_REFUSED));
        verify(provider, never()).query(any());
        verify(steps, never()).rejected(any(), any(), any(), any());
    }

    // ============================================================ a refusal nobody confirmed (HĐ-1, review finding 1)

    /** The provider reports a refusal of its processing alone (SePay's tracking "Failed"), as {@code code}. */
    private void reportedRefusal(String code, boolean unconfirmedByLookup) {
        when(provider.query(transaction)).thenReturn(new Result(ResultState.REJECTED, null, null, null, null, code,
                "Thông tin hóa đơn không hợp lệ"));
        when(provider.refusalUnconfirmedByLookup(code)).thenReturn(unconfirmedByLookup);
    }

    @Test
    @DisplayName("HĐ-1 (review): refused on the processing report alone, last sent under three hours ago - not "
            + "recorded: it stays at the provider, nothing is sent, it is asked about again")
    void aReportedRefusalIsNotRecordedTooSoon() {
        lastSent(Duration.ZERO);
        now(Duration.ofHours(3).minusSeconds(1));
        reportedRefusal(SepayEInvoiceAnswers.ISSUANCE_FAILED, true);

        EInvoice.Snapshot waiting = issuance.reconcile(id);

        assertThat(waiting.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        verify(steps, never()).rejected(any(), any(), any(), any());
        verify(provider, never()).submit(any());
    }

    @Test
    @DisplayName("HĐ-1 (review): three hours after its last send the provider still reports the refusal - recorded, "
            + "so the accountant may reopen, void or draw it up again without a second invoice following")
    void aReportedRefusalSettledIsRecorded() {
        lastSent(Duration.ofMinutes(5));
        now(Duration.ofHours(3).plusMinutes(5));
        reportedRefusal(SepayEInvoiceAnswers.ISSUANCE_FAILED, true);

        issuance.reconcile(id);

        verify(steps).rejected(id, transaction, SepayEInvoiceAnswers.ISSUANCE_FAILED, "Thông tin hóa đơn không hợp lệ");
        verify(provider, never()).submit(any());
    }

    @Test
    @DisplayName("HĐ-1 (review): reported refused, then issued by the provider inside the wait - recorded issued, as "
            + "any other invoice")
    void anAttemptIssuedDuringTheWaitIsRecordedIssued() {
        lastSent(Duration.ZERO);
        now(Duration.ofHours(1));
        reportedRefusal(SepayEInvoiceAnswers.ISSUANCE_FAILED, true);
        issuance.reconcile(id);
        verify(steps, never()).rejected(any(), any(), any(), any());

        Result issued = Result.of(ResultState.ISSUED);
        when(provider.query(transaction)).thenReturn(issued);
        now(Duration.ofHours(2));
        issuance.reconcile(id);

        verify(steps).issued(eq(id), eq(transaction), eq(issued), any());
        verify(steps, never()).rejected(any(), any(), any(), any());
    }

    @Test
    @DisplayName("HĐ-1 (review): a refusal the provider's look-up stood behind (a wrong tax code) is recorded at once")
    void aConfirmedRefusalIsRecordedAtOnce() {
        lastSent(Duration.ZERO);
        now(Duration.ofMinutes(1));
        reportedRefusal("INVALID_BUYER_TAX_CODE", false);

        issuance.reconcile(id);

        verify(steps).rejected(id, transaction, "INVALID_BUYER_TAX_CODE", "Thông tin hóa đơn không hợp lệ");
    }

    @Test
    @DisplayName("HĐ-1 (review): a reopened draft is issued at once whatever its last refusal was - a refusal is only "
            + "ever recorded settled, so issuing again needs no question of the provider")
    void aReopenedDraftIsIssuedAtOnce() {
        EInvoice refused = EInvoiceFixtures.draft();
        refused.submit(EInvoiceFixtures.SERIES, null, SUBMITTED);
        refused.rejected(SepayEInvoiceAnswers.ISSUANCE_FAILED, "Thông tin hóa đơn không hợp lệ");
        refused.reopen();
        EInvoice.Snapshot draft = refused.snapshot();
        when(steps.get(draft.id())).thenReturn(draft);
        now(Duration.ofMinutes(1));

        issuance.issue(draft.id(), draft.version(), null);

        verify(steps).submit(draft.id(), draft.version(), null);
        verify(provider, never()).query(any());
    }

    // ======================================================================= second review, findings 2b and 3

    @Test
    @DisplayName("second review, finding 3: a marked invoice (its delivery corrected to a failure) the provider has no "
            + "record of is asked about, never sent again - even inside the automatic resending")
    void aMarkedInvoiceIsNeverSentAgain() {
        lastSent(Duration.ZERO);
        now(Duration.ofMinutes(10));
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.NOT_FOUND));
        when(steps.markedDeliveryFailed(id)).thenReturn(true);

        EInvoice.Snapshot waiting = issuance.reconcile(id);

        assertThat(waiting.status()).isEqualTo(EInvoiceStatus.SUBMITTING);
        verify(provider, never()).submit(any());
        verify(steps, never()).aboutToSend(any(), any(), any(), any());
    }

    @Test
    @DisplayName("WF-05 BE-07 (Q-5): an answer that says nothing is never a reason to take an invoice back, however "
            + "long it lasts; only the provider's 'cannot confirm the result' is, after a day and on its support's "
            + "written answer, under its own code; 'still working' stays refused")
    void anUnknownResultIsWithdrawnOnlyWhenUnconfirmedAndOnTheSupportsWord() {
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.UNKNOWN));
        lastSent(Duration.ZERO);
        now(Duration.ofDays(30));
        assertThatThrownBy(() -> issuance.withdraw(id, invoice.version(), "SePay support: no invoice", null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.EINVOICE_WITHDRAWAL_REFUSED));

        Result unconfirmed = new Result(ResultState.UNKNOWN, null, null, null, null, "SEPAY_RESULT_UNCONFIRMED",
                "chua xac nhan duoc ket qua");
        when(provider.resultUnconfirmed("SEPAY_RESULT_UNCONFIRMED")).thenReturn(true);
        when(provider.query(transaction)).thenReturn(unconfirmed);
        now(Duration.ofDays(1).minusSeconds(1));
        assertThatThrownBy(() -> issuance.withdraw(id, invoice.version(), "stuck", "Ticket 4411", null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.EINVOICE_WITHDRAWAL_REFUSED));

        when(provider.query(transaction)).thenReturn(Result.of(ResultState.PENDING));
        now(Duration.ofDays(2));
        assertThatThrownBy(() -> issuance.withdraw(id, invoice.version(), "stuck", "Ticket 4411", null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.EINVOICE_WITHDRAWAL_REFUSED));
        verify(steps, never()).rejected(any(), any(), any(), any());

        when(provider.query(transaction)).thenReturn(unconfirmed);
        now(Duration.ofDays(1));
        assertThatThrownBy(() -> issuance.withdraw(id, invoice.version(), "stuck", null))
                .isInstanceOfSatisfying(BusinessException.class, refused -> assertThat(refused.errorCode())
                        .isEqualTo(ErrorCode.EINVOICE_WITHDRAWAL_EVIDENCE_REQUIRED));
        verify(steps, never()).rejected(any(), any(), any(), any());
        issuance.withdraw(id, invoice.version(), "SePay could not confirm", " Ticket 4411: no invoice issued ", null);
        verify(steps).rejected(id, transaction, EInvoice.WITHDRAWN_RESULT_UNCONFIRMED_CODE,
                "Ticket 4411: no invoice issued");
    }

    @Test
    @DisplayName("second review, finding 2b: withdraw-unknown-after shorter than withdraw-after is refused at startup")
    void theUnknownWaitIsNotShorterThanTheOther() {
        assertThatThrownBy(() -> new InvoicingProperties(Duration.ofDays(1), null, null, null, Duration.ofHours(3), 50,
                Duration.ofHours(1), Duration.ofHours(2))).isInstanceOf(IllegalArgumentException.class);
        assertThat(new InvoicingProperties(Duration.ofDays(1), null).withdrawUnknownAfter())
                .isEqualTo(Duration.ofDays(1));
    }
}
