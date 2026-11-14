package com.stockflow.notification.internal.service;

import com.stockflow.contracts.EInvoiceIssueDue;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** WF-05 E2: those who may issue e-invoices are told, once per invoice, that one is close to its deadline. */
class EInvoiceDueNotificationListenerTest {

    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final Inbox inbox = mock(Inbox.class);
    private final EInvoiceDueNotificationListener listener = new EInvoiceDueNotificationListener(staff, inbox);

    private static final EInvoiceIssueDue EVENT = new EInvoiceIssueDue(UUID.randomUUID(), "EINV-20261112-0001",
            UUID.randomUUID(), "SO-20261101-0007", LocalDate.of(2026, 11, 12), Instant.parse("2026-11-13T17:00:00Z"));

    @Test
    void theHoldersOfIssuanceAreToldOncePerInvoice() {
        UUID accountantId = UUID.randomUUID();
        StaffContact accountant = new StaffContact(accountantId, "ke.toan@example.com");
        when(staff.activeHolders("einvoices:APPROVE", null)).thenReturn(List.of(accountant));

        listener.on(EVENT);

        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(accountantId)), message.capture());
        InboxMessage sent = message.getValue();
        assertThat(sent.severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(sent.category()).isEqualTo("EINVOICE_DUE");
        // The deadline in Vietnam time: the end of Friday 13/11 is 00:00 on the 14th.
        assertThat(sent.title()).isEqualTo("Hoá đơn EINV-20261112-0001 phải phát hành trước 00:00 14/11/2026");
        assertThat(sent.body()).contains("SO-20261101-0007").contains("12/11/2026");
        assertThat(sent.link()).isEqualTo("/invoicing/einvoices/" + EVENT.einvoiceId());
        assertThat(sent.dedupeKey()).isEqualTo("einvoice-due:" + EVENT.einvoiceId());
        assertThat(sent.sourceId()).isEqualTo(EVENT.einvoiceId().toString());
    }

    @Test
    void nobodyToTellPostsNothing() {
        when(staff.activeHolders(any(), any())).thenReturn(List.of());

        listener.on(EVENT);

        verify(inbox, never()).post(any(), any());
    }
}
