package com.stockflow.notification.internal.service;

import com.stockflow.contracts.PaymentDueAtPacking;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.SalesAudiences;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RTM-PAYNOTIFY (owner decision 2026-11-19; kltn-docs 08 §4.4 step 9, §2): who hears that a shipment was packed with an
 * installment due before delivery still unpaid, and what they read. Of Sales, since 2026-11-28 whoever looks after the
 * order's customer (00 §6): the owner or the cover, else the Sales Managers marked "cần phân công".
 */
class PaymentDueAtPackingNotificationListenerTest {

    private static final String AUDIENCE = "fulfillment-packing:APPROVE";

    private final IdentityService identity = mock(IdentityService.class);
    private final SalesAudiences audiences = mock(SalesAudiences.class);
    private final Inbox inbox = mock(Inbox.class);
    private final PaymentDueAtPackingNotificationListener listener = new PaymentDueAtPackingNotificationListener(
            new StaffDirectory(identity), new SalesNotices(audiences, new StaffDirectory(identity),
            mock(com.stockflow.identity.api.SalesStaff.class)), inbox);

    private final UUID warehouse = UUID.randomUUID();
    private final UUID sales = UUID.randomUUID();
    private final PaymentDueAtPacking event = new PaymentDueAtPacking(UUID.randomUUID(), UUID.randomUUID(),
            "SO-0001", UUID.randomUUID(), "SO-0001-S1", warehouse, UUID.randomUUID(), new BigDecimal("1500000"), "VND",
            Instant.parse("2026-11-19T03:00:00Z"));

    @Test
    @DisplayName("the warehouse managers (by permission) and the customer's owner are told, once each")
    @SuppressWarnings("unchecked")
    void managersAndSalesAreTold() {
        UUID manager = UUID.randomUUID();
        when(identity.notificationRecipientsHolding(AUDIENCE, warehouse))
                .thenReturn(List.of(new StaffContact(manager, "wm@example.com")));
        when(audiences.ofOrder(event.orderId())).thenReturn(Optional.of(new SalesAudience(List.of(sales), false)));

        listener.on(event);

        ArgumentCaptor<Collection<UUID>> who = ArgumentCaptor.forClass(Collection.class);
        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox, org.mockito.Mockito.times(2)).post(who.capture(), message.capture());
        assertThat(who.getAllValues()).containsExactly(List.of(manager), List.of(sales));
        InboxMessage told = message.getAllValues().get(0);
        assertThat(told.category()).isEqualTo("PAYMENT_DUE_AT_PACKING");
        // Owner decision RTM-PAYNOTIFY-LEVEL (2026-11-19): a warning
        assertThat(told.severity()).isEqualTo(com.stockflow.notification.api.InboxSeverity.WARN);
        assertThat(told.title()).contains("SO-0001");
        assertThat(told.body()).contains("SO-0001-S1").contains("1.500.000 VND");
        assertThat(told.link()).isEqualTo("/admin/orders/" + event.orderId());
        assertThat(told.sourceId()).isEqualTo(event.shipmentId().toString());
        assertThat(told.dedupeKey()).isEqualTo("payment-due-at-packing:" + event.shipmentId());
        assertThat(message.getAllValues().get(1)).as("the owner reads the same notice, unmarked").isEqualTo(told);
    }

    @Test
    @DisplayName("§10.2: no active owner - the Sales Managers read it marked 'cần phân công'; a manager also on the "
            + "Sales side is told once")
    @SuppressWarnings("unchecked")
    void noOwnerManagersMarkedAndNoDuplicates() {
        UUID salesManager = UUID.randomUUID();
        when(identity.notificationRecipientsHolding(AUDIENCE, warehouse))
                .thenReturn(List.of(new StaffContact(sales, "both@example.com")));
        when(audiences.ofOrder(event.orderId()))
                .thenReturn(Optional.of(new SalesAudience(List.of(sales, salesManager), true)));

        listener.on(event);

        ArgumentCaptor<Collection<UUID>> who = ArgumentCaptor.forClass(Collection.class);
        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox, org.mockito.Mockito.times(2)).post(who.capture(), message.capture());
        assertThat(who.getAllValues()).containsExactly(List.of(sales), List.of(salesManager));
        assertThat(message.getAllValues().get(1).body()).contains("Cần phân công");
    }

    @Test
    @DisplayName("nobody to tell is logged, not thrown")
    void nobodyToTell() {
        when(identity.notificationRecipientsHolding(AUDIENCE, warehouse)).thenReturn(List.of());
        when(audiences.ofOrder(any())).thenReturn(Optional.empty());

        listener.on(event);

        verify(inbox, never()).post(any(), any());
    }
}
