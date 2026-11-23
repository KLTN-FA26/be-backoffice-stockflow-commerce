package com.stockflow.notification.internal.service;

import com.stockflow.contracts.EInvoiceProviderAccountAlert;
import com.stockflow.contracts.EInvoiceProviderAccountAlert.Problem;
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
 * HĐ-1 (kltn-docs 21 BR-09): those who issue e-invoices are told that the provider's account needs them on its portal -
 * once per cause and day, not once per refused invoice.
 */
class EInvoiceProviderAccountNotificationListenerTest {

    /** 23:30 on the 12th in Viet Nam: still the 12th there. */
    private static final Instant LATE_ON_THE_12TH = Instant.parse("2026-11-12T16:30:00Z");

    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final Inbox inbox = mock(Inbox.class);
    private final EInvoiceProviderAccountNotificationListener listener =
            new EInvoiceProviderAccountNotificationListener(staff, inbox);
    private final UUID accountant = UUID.randomUUID();

    private InboxMessage told(EInvoiceProviderAccountAlert alert) {
        when(staff.activeHolders("einvoices:APPROVE", null)).thenReturn(List.of(
                new StaffContact(accountant, "ke.toan@example.com")));
        listener.on(alert);
        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(accountant)), message.capture());
        return message.getValue();
    }

    @Test
    @DisplayName("a used-up plan: critical, names the provider and the code the refused invoices carry")
    void aUsedUpPlanIsAnnounced() {
        InboxMessage sent = told(new EInvoiceProviderAccountAlert("SEPAY_EINVOICE", Problem.QUOTA_EXHAUSTED,
                "SEPAY_QUOTA_EXHAUSTED", null, null, LATE_ON_THE_12TH));

        assertThat(sent.category()).isEqualTo("EINVOICE_PROVIDER_ACCOUNT");
        assertThat(sent.severity()).isEqualTo(InboxSeverity.CRITICAL);
        assertThat(sent.title()).isEqualTo("Gói hoá đơn điện tử ở SEPAY_EINVOICE đã hết lượt");
        // WF-05 BE-54 (Q-22 BR-09): issuing pauses, the drafts wait as drafts and go out once the plan is topped up.
        assertThat(sent.body()).contains("SEPAY_QUOTA_EXHAUSTED", "tạm dừng phát hành", "các nháp giữ nguyên");
        assertThat(sent.link()).isEqualTo("/invoicing/einvoices?status=DRAFT");
        assertThat(sent.dedupeKey()).isEqualTo("einvoice-account:SEPAY_EINVOICE:QUOTA_EXHAUSTED:2026-11-12");
    }

    @Test
    @DisplayName("a plan running low: a warning with what is left and the threshold")
    void aPlanRunningLowIsAWarning() {
        InboxMessage sent = told(new EInvoiceProviderAccountAlert("SEPAY_EINVOICE", Problem.QUOTA_LOW, null, 12L, 50L,
                LATE_ON_THE_12TH));

        assertThat(sent.severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(sent.body()).contains("còn 12 lượt", "ngưỡng cảnh báo 50");
        assertThat(sent.dedupeKey()).isEqualTo("einvoice-account:SEPAY_EINVOICE:QUOTA_LOW:2026-11-12");
    }

    @Test
    @DisplayName("every cause has its words, and one message a day per cause: the next day is told again")
    void everyCauseOnceADay() {
        for (Problem problem : Problem.values()) {
            InboxMessage message = EInvoiceProviderAccountNotificationListener.message(
                    new EInvoiceProviderAccountAlert("SEPAY_EINVOICE", problem, "SEPAY_X", 1L, 2L, LATE_ON_THE_12TH));
            assertThat(message.title()).as(problem.name()).doesNotContain("%");
            assertThat(message.body()).as(problem.name()).doesNotContain("%");
        }
        String today = EInvoiceProviderAccountNotificationListener.message(new EInvoiceProviderAccountAlert(
                "SEPAY_EINVOICE", Problem.BILLING_UNPAID, "SEPAY_BILLING_UNPAID", null, null, LATE_ON_THE_12TH))
                .dedupeKey();
        String tomorrow = EInvoiceProviderAccountNotificationListener.message(new EInvoiceProviderAccountAlert(
                "SEPAY_EINVOICE", Problem.BILLING_UNPAID, "SEPAY_BILLING_UNPAID", null, null,
                LATE_ON_THE_12TH.plusSeconds(3600))).dedupeKey();
        assertThat(tomorrow).isNotEqualTo(today);
    }

    @Test
    @DisplayName("nobody may issue invoices: nothing is posted, and nothing fails")
    void nobodyToTell() {
        when(staff.activeHolders(any(), any())).thenReturn(List.of());

        listener.on(new EInvoiceProviderAccountAlert("SEPAY_EINVOICE", Problem.ACCOUNT_NOT_READY,
                "SEPAY_ACCOUNT_NOT_READY", null, null, LATE_ON_THE_12TH));

        verify(inbox, never()).post(any(), any());
    }
}
