package com.stockflow.invoicing.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.invoicing.internal.domain.EInvoice;
import com.stockflow.invoicing.internal.domain.EInvoiceFixtures;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.Result;
import com.stockflow.invoicing.internal.domain.EInvoiceProvider.ResultState;
import com.stockflow.invoicing.internal.domain.EInvoiceRepository;
import com.stockflow.invoicing.internal.domain.EInvoiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 BE-07 and BE-59 (decision Q-5; invariant V2: a withdrawal is final only on the provider's word, and what was
 * let go is watched for 30 days): when an invoice stuck at the provider may be taken back, what a refusal tells the
 * screen, and the daily watch of transactions let go.
 */
class EInvoiceWithdrawalRulesTest {

    private static final Instant SUBMITTED = EInvoiceFixtures.NOW;

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
    void anInvoiceAtTheProviderSentLongAgo() {
        invoice = EInvoiceFixtures.in(EInvoiceStatus.SUBMITTING).snapshot();
        id = invoice.id();
        transaction = invoice.transactionId();
        when(steps.get(id)).thenReturn(invoice);
        when(steps.claim(eq(id), any(UUID.class), any(Duration.class))).thenReturn(true);
        when(providers.require(invoice.provider())).thenReturn(provider);
        when(steps.lastSent(id, transaction)).thenReturn(Optional.of(SUBMITTED));
        when(clock.instant()).thenReturn(SUBMITTED.plus(Duration.ofDays(3)));
        when(provider.resultUnconfirmed("SEPAY_RESULT_UNCONFIRMED")).thenReturn(true);
    }

    private BusinessException refusal(String supportReference) {
        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> issuance.withdraw(id, invoice.version(), "checked the portal", supportReference, null));
        assertThat(thrown).isInstanceOf(BusinessException.class);
        return (BusinessException) thrown;
    }

    @Test
    @DisplayName("BE-07: SePay holds it without its number yet (PENDING) - never taken back, whatever the wait; the "
            + "refusal says what the provider said and gives no day it could be")
    void anInvoiceTheProviderHoldsIsNeverTakenBack() {
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.PENDING));
        BusinessException refused = refusal("Ticket 1");
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.EINVOICE_WITHDRAWAL_REFUSED);
        assertThat(refused.details()).containsEntry("providerState", "PENDING").doesNotContainKey("withdrawableFrom");
        verify(steps, never()).rejected(any(), any(), any(), any());
    }

    @Test
    @DisplayName("BE-07: an answer that says nothing (a 4xx, an unread body) is never a reason, even a month on")
    void anUnreadAnswerIsNeverAReason() {
        when(clock.instant()).thenReturn(SUBMITTED.plus(Duration.ofDays(30)));
        when(provider.query(transaction)).thenReturn(new Result(ResultState.UNKNOWN, null, null, null, null,
                "HTTP_403", "forbidden"));
        BusinessException refused = refusal("Ticket 1");
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.EINVOICE_WITHDRAWAL_REFUSED);
        assertThat(refused.details()).containsEntry("providerState", "UNKNOWN");
        verify(steps, never()).rejected(any(), any(), any(), any());
    }

    @Test
    @DisplayName("BE-07: 'cannot confirm the result' is taken back only on SePay support's written answer, under "
            + "WITHDRAWN_RESULT_UNCONFIRMED with the answer as its message")
    void anUnconfirmedResultNeedsTheSupportsAnswer() {
        when(provider.query(transaction)).thenReturn(new Result(ResultState.UNKNOWN, null, null, null, null,
                "SEPAY_RESULT_UNCONFIRMED", "chua xac nhan duoc ket qua"));
        BusinessException withoutEvidence = refusal(null);
        assertThat(withoutEvidence.errorCode()).isEqualTo(ErrorCode.EINVOICE_WITHDRAWAL_EVIDENCE_REQUIRED);
        assertThat(withoutEvidence.details()).containsEntry("providerState", "UNCONFIRMED");
        verify(steps, never()).rejected(any(), any(), any(), any());

        issuance.withdraw(id, invoice.version(), "checked the portal", "SePay ticket 7781", null);
        verify(steps).rejected(id, transaction, EInvoice.WITHDRAWN_RESULT_UNCONFIRMED_CODE, "SePay ticket 7781");
    }

    @Test
    @DisplayName("BE-59: too early for an unconfirmed result - the refusal names the day it may be taken back")
    void tooEarlyNamesTheDay() {
        when(clock.instant()).thenReturn(SUBMITTED.plus(Duration.ofHours(5)));
        when(provider.query(transaction)).thenReturn(new Result(ResultState.UNKNOWN, null, null, null, null,
                "SEPAY_RESULT_UNCONFIRMED", null));
        BusinessException refused = refusal("Ticket 1");
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.EINVOICE_WITHDRAWAL_REFUSED);
        assertThat(refused.details()).containsEntry("providerState", "UNCONFIRMED")
                .containsEntry("withdrawableFrom", SUBMITTED.plus(Duration.ofDays(1)).toString());
    }

    @Test
    @DisplayName("BE-59: sent too recently - the provider is not asked, the refusal names when, and no state")
    void tooRecentNamesWhen() {
        when(clock.instant()).thenReturn(SUBMITTED.plus(Duration.ofHours(1)));
        BusinessException refused = refusal(null);
        assertThat(refused.details()).containsEntry("withdrawableFrom", SUBMITTED.plus(Duration.ofHours(3)).toString())
                .doesNotContainKey("providerState");
        verify(provider, never()).query(any());
    }

    @Test
    @DisplayName("BE-07: 'no record' today, but the provider once answered it holds the transaction (a duplicate, a "
            + "look-up that found it issued) - never taken back")
    void evidenceInTheCallLogWins() {
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.NOT_FOUND));
        when(calls.providerHeld(id, transaction)).thenReturn(true);
        BusinessException refused = refusal(null);
        assertThat(refused.errorCode()).isEqualTo(ErrorCode.EINVOICE_WITHDRAWAL_REFUSED);
        assertThat(refused.details()).containsEntry("providerState", "NOT_FOUND");
        verify(steps, never()).rejected(any(), any(), any(), any());

        when(calls.providerHeld(id, transaction)).thenReturn(false);
        issuance.withdraw(id, invoice.version(), "SePay portal: nothing under it", null);
        verify(steps).rejected(id, transaction, EInvoice.WITHDRAWN_CODE, "SePay portal: nothing under it");
    }

    @Test
    @DisplayName("BE-07 (V2): a transaction let go is asked about; found issued after all, it is recorded and told "
            + "once; not issued, it is noted as asked; a provider that does not answer is asked next time")
    void letGoTransactionsAreWatched() {
        EInvoiceRepository.WatchedTransaction issued = new EInvoiceRepository.WatchedTransaction(id, transaction,
                "SEPAY_EINVOICE", EInvoice.WITHDRAWN_CODE);
        EInvoiceRepository.WatchedTransaction nothing = new EInvoiceRepository.WatchedTransaction(UUID.randomUUID(),
                "t-2", "SEPAY_EINVOICE", EInvoice.WITHDRAWN_RESULT_UNCONFIRMED_CODE);
        EInvoiceRepository.WatchedTransaction silent = new EInvoiceRepository.WatchedTransaction(UUID.randomUUID(),
                "t-3", "SEPAY_EINVOICE", "SEPAY_ISSUANCE_FAILED");
        when(steps.watchedToAsk(50)).thenReturn(List.of(issued, nothing, silent));
        when(providers.find("SEPAY_EINVOICE")).thenReturn(Optional.of(provider));
        Result found = new Result(ResultState.ISSUED, 42, "M1-26-XYZ", "LOOK", SUBMITTED, null, null);
        when(provider.query(transaction)).thenReturn(found);
        when(provider.query("t-2")).thenReturn(Result.of(ResultState.NOT_FOUND));
        when(provider.query("t-3")).thenThrow(new EInvoiceProvider.ProviderUnavailableException("down"));
        when(steps.watchedTransactionIssued(id, transaction, found)).thenReturn(true);

        assertThat(issuance.watchLetGoTransactions(50)).isEqualTo(1);

        verify(steps).watchedTransactionIssued(id, transaction, found);
        verify(steps).watchedTransactionAsked(nothing.einvoiceId(), "t-2");
        verify(steps, never()).watchedTransactionAsked(silent.einvoiceId(), "t-3");
    }
    private EInvoiceIssuance.PortalRecord refusedOnThePortal() {
        when(providers.usable(invoice.provider())).thenReturn(true);
        when(providers.find(invoice.provider())).thenReturn(Optional.of(provider));
        return new EInvoiceIssuance.PortalRecord(EInvoiceIssuance.PortalOutcome.REFUSED_BY_TAX_AUTHORITY, null, null,
                null, null, null, "the tax authority refused it, see the portal");
    }

    @Test
    @DisplayName("BE-08 / Q-5: the tax authority's refusal is not recorded from the portal while SePay holds the "
            + "invoice (issued without its number yet, or still processing): it would be sent again under a new "
            + "transaction while the old one may still become an invoice")
    void aRefusalIsNotRecordedWhileTheProviderHoldsTheInvoice() {
        EInvoiceIssuance.PortalRecord refused = refusedOnThePortal();
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.PENDING));

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
                () -> issuance.recordPortalResult(id, invoice.version(), refused, null));

        assertThat(((BusinessException) thrown).errorCode()).isEqualTo(ErrorCode.EINVOICE_PORTAL_RECORD_MISMATCH);
        verify(steps, never()).rejected(any(), any(), any(), any());
    }

    @Test
    @DisplayName("BE-08: at a provider whose PENDING may be the tax authority's refusal (Viettel keeps a refused entry "
            + "numbered without a code), the accountant's record of it is the way out")
    void aRefusalIsRecordedWhereOnlyThePortalShowsIt() {
        EInvoiceIssuance.PortalRecord refused = refusedOnThePortal();
        when(provider.query(transaction)).thenReturn(Result.of(ResultState.PENDING));
        when(provider.pendingMayBeRefusedByTaxAuthority()).thenReturn(true);

        issuance.recordPortalResult(id, invoice.version(), refused, null);

        verify(steps).rejected(id, transaction, EInvoice.REFUSED_BY_TAX_AUTHORITY_CODE,
                "the tax authority refused it, see the portal");
    }
}
