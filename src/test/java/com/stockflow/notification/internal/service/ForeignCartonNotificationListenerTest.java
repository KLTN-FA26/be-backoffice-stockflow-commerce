package com.stockflow.notification.internal.service;

import com.stockflow.contracts.ForeignCartonScanned;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
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

/** WF4-LPN-FOREIGN: who hears of a foreign carton scanned at the check station, and what they read. */
class ForeignCartonNotificationListenerTest {

    private final IdentityService identity = mock(IdentityService.class);
    private final Inbox inbox = mock(Inbox.class);
    private final ForeignCartonNotificationListener listener =
            new ForeignCartonNotificationListener(new StaffDirectory(identity), inbox);

    private final UUID warehouse = UUID.randomUUID();
    private final ForeignCartonScanned event = new ForeignCartonScanned(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), "SO-0001", warehouse, "LPN-OF-ANOTHER-ORDER", UUID.randomUUID(),
            Instant.parse("2026-11-15T03:00:00Z"));

    @Test
    void theWarehouseManagersOfTheTasksWarehouseAreToldOncePerTaskAndCode() {
        UUID manager = UUID.randomUUID();
        when(identity.notificationRecipientsHolding("fulfillment-packing:APPROVE", warehouse))
                .thenReturn(List.of(new StaffContact(manager, "wm@example.com")));
        when(inbox.post(any(), any())).thenReturn(1);

        listener.on(event);

        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(List.of(manager)), message.capture());
        assertThat(message.getValue().category()).isEqualTo("CARTON_CHECK");
        assertThat(message.getValue().title()).contains("SO-0001");
        assertThat(message.getValue().body()).contains("LPN-OF-ANOTHER-ORDER");
        assertThat(message.getValue().link()).isEqualTo("/admin/orders/" + event.orderId());
        assertThat(message.getValue().sourceId()).isEqualTo(event.taskId().toString());
        assertThat(message.getValue().dedupeKey())
                .isEqualTo("foreign-carton:" + event.taskId() + ":LPN-OF-ANOTHER-ORDER");
    }

    @Test
    void nobodyToTellIsLoggedNotThrown() {
        when(identity.notificationRecipientsHolding("fulfillment-packing:APPROVE", warehouse)).thenReturn(List.of());

        listener.on(event);

        verify(inbox, never()).post(any(), any());
    }
}
