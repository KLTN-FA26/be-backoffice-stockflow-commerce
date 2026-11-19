package com.stockflow.notification.internal.service;

import com.stockflow.contracts.PaymentDueAtPacking;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.identity.api.StaffUser;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RTM-PAYNOTIFY (owner decision 2026-11-19; kltn-docs 08 §4.4 step 9, §2): who hears that a shipment was packed with an
 * installment due before delivery still unpaid, and what they read.
 */
class PaymentDueAtPackingNotificationListenerTest {

    private static final String AUDIENCE = "fulfillment-packing:APPROVE";

    private final IdentityService identity = mock(IdentityService.class);
    private final Inbox inbox = mock(Inbox.class);
    private final PaymentDueAtPackingNotificationListener listener =
            new PaymentDueAtPackingNotificationListener(new StaffDirectory(identity), inbox);

    private final UUID warehouse = UUID.randomUUID();
    private final UUID sales = UUID.randomUUID();
    private final PaymentDueAtPacking event = new PaymentDueAtPacking(UUID.randomUUID(), UUID.randomUUID(),
            "SO-0001", UUID.randomUUID(), "SO-0001-S1", warehouse, sales, new BigDecimal("1500000"), "VND",
            Instant.parse("2026-11-19T03:00:00Z"));

    private static StaffUser user(UUID id, String status) {
        return new StaffUser(id, "sales1", "sales1@example.com", "Sales One", status, List.of(), List.of(), false, null,
                null, null, null, null, 0);
    }

    @SuppressWarnings("unchecked")
    private Collection<UUID> recipients(ArgumentCaptor<Collection<UUID>> captor) {
        return captor.getValue();
    }

    @Test
    @DisplayName("the warehouse managers of the shipment's warehouse (by permission) and the order's Sales are told once")
    void managersAndSalesAreTold() {
        UUID manager = UUID.randomUUID();
        when(identity.notificationRecipientsHolding(AUDIENCE, warehouse))
                .thenReturn(List.of(new StaffContact(manager, "wm@example.com")));
        when(identity.user(sales)).thenReturn(user(sales, "ACTIVE"));
        when(inbox.post(any(), any())).thenReturn(2);

        listener.on(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> who = ArgumentCaptor.forClass(Collection.class);
        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(who.capture(), message.capture());
        assertThat(recipients(who)).containsExactly(manager, sales);
        assertThat(message.getValue().category()).isEqualTo("PAYMENT_DUE_AT_PACKING");
        // Owner decision RTM-PAYNOTIFY-LEVEL (2026-11-19): a warning
        assertThat(message.getValue().severity()).isEqualTo(com.stockflow.notification.api.InboxSeverity.WARN);
        assertThat(message.getValue().title()).contains("SO-0001");
        assertThat(message.getValue().body()).contains("SO-0001-S1").contains("1.500.000 VND");
        assertThat(message.getValue().link()).isEqualTo("/admin/orders/" + event.orderId());
        assertThat(message.getValue().sourceId()).isEqualTo(event.shipmentId().toString());
        assertThat(message.getValue().dedupeKey()).isEqualTo("payment-due-at-packing:" + event.shipmentId());
    }

    @Test
    @DisplayName("a Sales account no longer active is left out; a manager who is also the Sales is told once")
    void inactiveSalesLeftOutAndNoDuplicates() {
        when(identity.notificationRecipientsHolding(AUDIENCE, warehouse))
                .thenReturn(List.of(new StaffContact(sales, "both@example.com")));
        when(identity.user(sales)).thenReturn(user(sales, "INACTIVE"));

        listener.on(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> who = ArgumentCaptor.forClass(Collection.class);
        verify(inbox).post(who.capture(), any());
        assertThat(recipients(who)).containsExactly(sales);
    }

    @Test
    @DisplayName("nobody to tell is logged, not thrown")
    void nobodyToTell() {
        PaymentDueAtPacking noSales = new PaymentDueAtPacking(event.eventId(), event.orderId(), "SO-0001",
                event.shipmentId(), "SO-0001-S1", warehouse, null, BigDecimal.TEN, "VND", event.completedAt());
        when(identity.notificationRecipientsHolding(AUDIENCE, warehouse)).thenReturn(List.of());

        listener.on(noSales);

        verify(inbox, never()).post(any(), any());
    }
}
