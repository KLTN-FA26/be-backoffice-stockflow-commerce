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
    private final EInvoiceIssuance issuance = new EInvoiceIssuance(steps, providers, calls, clock,
            mock(AsyncTaskExecutor.class));
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
}
