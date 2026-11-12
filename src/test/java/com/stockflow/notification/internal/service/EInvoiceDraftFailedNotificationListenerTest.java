package com.stockflow.notification.internal.service;

import com.stockflow.contracts.EInvoiceDraftFailed;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** WF-05 E2: a delivery whose draft e-invoice was refused is put in front of those who can draw it up by hand. */
class EInvoiceDraftFailedNotificationListenerTest {

    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final Inbox inbox = mock(Inbox.class);
    private final EInvoiceDraftFailedNotificationListener listener =
            new EInvoiceDraftFailedNotificationListener(staff, inbox);

    private static final EInvoiceDraftFailed EVENT = new EInvoiceDraftFailed(UUID.randomUUID(), "SO-20261101-0007",
            UUID.randomUUID(), "SELLER_PROFILE_UNAVAILABLE");

    @Test
    void thoseWhoDrawInvoicesUpAreToldOncePerShipment() {
        UUID accountantId = UUID.randomUUID();
        when(staff.activeHolders("einvoices:CREATE", null))
                .thenReturn(List.of(new StaffContact(accountantId, "ke.toan@example.com")));

        listener.on(EVENT);

        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(accountantId)), message.capture());
        InboxMessage sent = message.getValue();
        assertThat(sent.severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(sent.category()).isEqualTo("EINVOICE_DRAFT_FAILED");
        assertThat(sent.title()).contains("SO-20261101-0007");
        assertThat(sent.body()).contains("SO-20261101-0007").contains("SELLER_PROFILE_UNAVAILABLE");
        assertThat(sent.link()).isEqualTo("/invoicing/einvoices?orderId=" + EVENT.orderId());
        assertThat(sent.dedupeKey()).isEqualTo("einvoice-draft-failed:" + EVENT.shipmentId());
    }

    @Test
    void nobodyToTellPostsNothing() {
        when(staff.activeHolders(any(), any())).thenReturn(List.of());

        listener.on(EVENT);

        verify(inbox, never()).post(any(), any());
    }
}
