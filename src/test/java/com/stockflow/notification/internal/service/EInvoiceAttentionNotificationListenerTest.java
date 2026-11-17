package com.stockflow.notification.internal.service;

import com.stockflow.contracts.EInvoiceNeedsAttention;
import com.stockflow.contracts.EInvoiceNeedsAttention.Cause;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 E5 (plan §1.4 "Bị từ chối hoặc lỗi thì báo kế toán"): those who may issue e-invoices are told of one that was
 * refused and of one stuck at the provider - once per submission, in system text only.
 */
class EInvoiceAttentionNotificationListenerTest {

    private static final UUID INVOICE = UUID.randomUUID();
    private static final Instant SUBMITTED = Instant.parse("2026-11-12T02:15:00Z");
    private static final Instant DUE = Instant.parse("2026-11-13T17:00:00Z");

    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final Inbox inbox = mock(Inbox.class);
    private final EInvoiceAttentionNotificationListener listener = new EInvoiceAttentionNotificationListener(staff,
            inbox);
    private final UUID accountant = UUID.randomUUID();

    private static EInvoiceNeedsAttention event(Cause cause, String rejectionCode, Instant submittedAt) {
        return new EInvoiceNeedsAttention(INVOICE, "EINV-20261112-0001", UUID.randomUUID(), "SO-20261101-0007", cause,
                rejectionCode, submittedAt, DUE);
    }

    private InboxMessage told(EInvoiceNeedsAttention event) {
        when(staff.activeHolders("einvoices:APPROVE", null)).thenReturn(List.of(
                new StaffContact(accountant, "ke.toan@example.com")));
        listener.on(event);
        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(accountant)), message.capture());
        return message.getValue();
    }

    @Test
    @DisplayName("REJECTED: critical, names the invoice, the order, the provider's code and the deadline in Vietnam time")
    void aRefusalIsAnnounced() {
        InboxMessage sent = told(event(Cause.REJECTED, "INVALID_BUYER_TAX_CODE", SUBMITTED));

        assertThat(sent.category()).isEqualTo("EINVOICE_ATTENTION");
        assertThat(sent.severity()).isEqualTo(InboxSeverity.CRITICAL);
        assertThat(sent.title()).isEqualTo("Hoá đơn EINV-20261112-0001 bị từ chối");
        assertThat(sent.body()).contains("EINV-20261112-0001", "SO-20261101-0007", "INVALID_BUYER_TAX_CODE",
                "00:00 14/11/2026");
        assertThat(sent.link()).isEqualTo("/invoicing/einvoices/" + INVOICE);
        assertThat(sent.sourceId()).isEqualTo(INVOICE.toString());
        assertThat(sent.dedupeKey()).isEqualTo("einvoice-rejected:" + INVOICE + ":" + SUBMITTED.toEpochMilli());
    }

    @Test
    @DisplayName("a refusal without a provider code is still announced")
    void aRefusalWithoutACode() {
        InboxMessage sent = told(event(Cause.REJECTED, null, SUBMITTED));

        assertThat(sent.severity()).isEqualTo(InboxSeverity.CRITICAL);
        assertThat(sent.body()).contains("EINV-20261112-0001").doesNotContain("null");
    }

    @Test
    @DisplayName("STUCK_SUBMITTING: a warning that says when it was sent and when it is due")
    void aStuckSubmissionIsAnnounced() {
        InboxMessage sent = told(event(Cause.STUCK_SUBMITTING, null, SUBMITTED));

        assertThat(sent.category()).isEqualTo("EINVOICE_ATTENTION");
        assertThat(sent.severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(sent.title()).isEqualTo("Hoá đơn EINV-20261112-0001 chưa có kết quả phát hành");
        assertThat(sent.body()).contains("SO-20261101-0007", "09:15 12/11/2026", "00:00 14/11/2026");
        assertThat(sent.link()).isEqualTo("/invoicing/einvoices/" + INVOICE);
        assertThat(sent.dedupeKey()).isEqualTo("einvoice-stuck:" + INVOICE + ":" + SUBMITTED.toEpochMilli());
    }

    @Test
    @DisplayName("UNKNOWN_TO_PROVIDER: critical, asks for a fresh send and keys on its own cause")
    void anInvoiceUnknownToTheProviderIsAnnounced() {
        InboxMessage sent = told(event(Cause.UNKNOWN_TO_PROVIDER, null, SUBMITTED));

        assertThat(sent.category()).isEqualTo("EINVOICE_ATTENTION");
        assertThat(sent.severity()).isEqualTo(InboxSeverity.CRITICAL);
        assertThat(sent.title()).isEqualTo("Hoá đơn EINV-20261112-0001 cần gửi lại");
        assertThat(sent.body()).contains("SO-20261101-0007", "09:15 12/11/2026", "00:00 14/11/2026");
        assertThat(sent.link()).isEqualTo("/invoicing/einvoices/" + INVOICE);
        assertThat(sent.dedupeKey()).isEqualTo("einvoice-unknown:" + INVOICE + ":" + SUBMITTED.toEpochMilli());
    }

    @Test
    @DisplayName("one message per submission and cause: a redelivery repeats the key, the next attempt and the other "
            + "cause have their own")
    void oncePerSubmissionAndCause() {
        String rejected = EInvoiceAttentionNotificationListener.message(event(Cause.REJECTED, "X", SUBMITTED))
                .dedupeKey();
        assertThat(EInvoiceAttentionNotificationListener.message(event(Cause.REJECTED, "X", SUBMITTED)).dedupeKey())
                .as("the same event again").isEqualTo(rejected);
        assertThat(EInvoiceAttentionNotificationListener.message(event(Cause.REJECTED, "X",
                SUBMITTED.plusSeconds(600))).dedupeKey()).as("refused again after a correction").isNotEqualTo(rejected);
        assertThat(EInvoiceAttentionNotificationListener.message(event(Cause.STUCK_SUBMITTING, null, SUBMITTED))
                .dedupeKey()).as("stuck, then refused: two things to hear").isNotEqualTo(rejected);
    }

    @Test
    @DisplayName("nobody may issue invoices: nothing is posted, and nothing fails")
    void nobodyToTell() {
        when(staff.activeHolders(any(), any())).thenReturn(List.of());

        listener.on(event(Cause.REJECTED, "X", SUBMITTED));
        listener.on(event(Cause.STUCK_SUBMITTING, null, SUBMITTED));

        verify(inbox, never()).post(any(), any());
    }
}
