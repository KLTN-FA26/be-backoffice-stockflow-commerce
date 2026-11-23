package com.stockflow.notification.internal.service;

import com.stockflow.common.security.Role;
import com.stockflow.contracts.ReturnAwaitingRefund;
import com.stockflow.contracts.ReturnReceived;
import com.stockflow.contracts.ReturnedCartonsInQuarantine;
import com.stockflow.contracts.ReturnedGoodsReceived;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WF-05 BE-24 (audit R-9): staff are told what a return leaves them to do - a refund to propose, a parcel that differs
 * from what was approved, a carrier's return with cartons missing, returned cartons waiting in quarantine - each once,
 * to the people who may act on it, in the words of the {@code _vi} bundle.
 */
class ReturnStaffNotificationListenerTest {

    private static final Instant AT = Instant.parse("2026-11-23T03:00:00Z");

    private final IdentityService identity = mock(IdentityService.class);
    private final Inbox inbox = mock(Inbox.class);
    private final NotificationSender sender = mock(NotificationSender.class);
    private final ReturnStaffNotificationListener listener = new ReturnStaffNotificationListener(sender,
            new StaffDirectory(identity), new StaffNotices(inbox));

    private final UUID sales = UUID.randomUUID();
    private final UUID returnId = UUID.randomUUID();

    private InboxMessage posted(List<UUID> to) {
        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(to), message.capture());
        return message.getValue();
    }

    @Test
    @DisplayName("a return waits for its refund: whoever proposes refunds is told, keyed by return and moment, by mail too")
    void awaitingProposal() {
        when(identity.notificationRecipientsHolding("sales-rma-resolutions:UPDATE", null))
                .thenReturn(List.of(new StaffContact(sales, "sales@example.com")));
        when(inbox.post(any(), any())).thenReturn(1);

        listener.on(new ReturnAwaitingRefund(returnId, "RMA-20261123-0001", UUID.randomUUID(), "SO-0042",
                UUID.randomUUID(), "GOODS_COMPLETE", AT));

        InboxMessage message = posted(List.of(sales));
        assertThat(message.category()).isEqualTo("RETURN");
        assertThat(message.title()).contains("RMA-20261123-0001");
        assertThat(message.body()).contains("RMA-20261123-0001").contains("SO-0042");
        assertThat(message.link()).isEqualTo("/admin/orders/returns/" + returnId);
        assertThat(message.dedupeKey()).isEqualTo("return-awaiting-proposal:" + returnId + ":" + AT.toEpochMilli());
        verify(sender).sendStaffNotice(eq(List.of("sales@example.com")), eq(message),
                eq("staff_return_awaiting_proposal"));
    }

    @Test
    @DisplayName("a parcel with a discrepancy is reported to Sales with its receipt; one without is not")
    void discrepancy() {
        when(identity.notificationRecipientsHolding("sales-rma-resolutions:UPDATE", null))
                .thenReturn(List.of(new StaffContact(sales, null)));
        when(inbox.post(any(), any())).thenReturn(1);
        ReturnReceived clean = new ReturnReceived(returnId, "RMA-1", UUID.randomUUID(), "SO-1", UUID.randomUUID(),
                "HCM-QC01", List.of(), false, UUID.randomUUID(), AT, 1, "RECEIVED", "GR-RET-0001");
        listener.on(clean);
        verify(inbox, never()).post(anyList(), any());

        listener.on(new ReturnReceived(returnId, "RMA-1", UUID.randomUUID(), "SO-1", UUID.randomUUID(), "HCM-QC01",
                List.of(), true, UUID.randomUUID(), AT, 2, "AWAITING_RETURN", "GR-RET-0002"));
        InboxMessage message = posted(List.of(sales));
        assertThat(message.body()).contains("GR-RET-0002");
        assertThat(message.dedupeKey()).isEqualTo("return-discrepancy:" + returnId + ":2");
        verify(sender, never()).sendStaffNotice(anyList(), any(), any());
    }

    @Test
    @DisplayName("a carrier's return received with cartons missing: who records delivery outcomes at that warehouse")
    void missingCartons() {
        UUID warehouse = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        when(identity.notificationRecipientsHolding("fulfillment-delivery-outcomes:APPROVE", warehouse))
                .thenReturn(List.of(new StaffContact(manager, null)));
        when(inbox.post(any(), any())).thenReturn(1);
        UUID receipt = UUID.randomUUID();
        ReturnedGoodsReceived event = new ReturnedGoodsReceived(receipt, "GR-RET-0009", warehouse, UUID.randomUUID(),
                "SO-9", UUID.randomUUID(), UUID.randomUUID(), null, "HCM-QC01", List.of(), UUID.randomUUID(), AT,
                List.of(), List.of("LPN-A", "LPN-B"));

        listener.on(event);

        InboxMessage message = posted(List.of(manager));
        assertThat(message.title()).contains("SO-9");
        assertThat(message.body()).contains("GR-RET-0009").contains("2");
        assertThat(message.dedupeKey()).isEqualTo("return-missing-cartons:" + receipt);
    }

    @Test
    @DisplayName("nothing missing, or a return request's receipt: nobody is told")
    void nothingMissing() {
        listener.on(new ReturnedGoodsReceived(UUID.randomUUID(), "GR-RET-1", UUID.randomUUID(), UUID.randomUUID(),
                "SO-9", UUID.randomUUID(), UUID.randomUUID(), null, "HCM-QC01", List.of(), UUID.randomUUID(), AT,
                List.of(), List.of()));
        listener.on(new ReturnedGoodsReceived(UUID.randomUUID(), "GR-RET-2", UUID.randomUUID(), UUID.randomUUID(),
                "SO-9", UUID.randomUUID(), null, UUID.randomUUID(), "HCM-QC01", List.of(), UUID.randomUUID(), AT,
                List.of(), List.of("LPN-X")));
        verify(inbox, never()).post(anyList(), any());
    }

    @Test
    @DisplayName("the daily quarantine digest goes to that warehouse's managers, once a day; none when nothing waits")
    void quarantineDigest() {
        UUID warehouse = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        when(identity.notificationRecipients(Set.of(Role.WAREHOUSE_MANAGER.name()), warehouse))
                .thenReturn(List.of(new StaffContact(manager, null)));
        when(inbox.post(any(), any())).thenReturn(1);
        LocalDate day = LocalDate.of(2026, 11, 23);

        listener.on(new ReturnedCartonsInQuarantine(warehouse, "HCM", 0, day));
        verify(inbox, never()).post(anyList(), any());

        listener.on(new ReturnedCartonsInQuarantine(warehouse, "HCM", 4, day));
        InboxMessage message = posted(List.of(manager));
        assertThat(message.category()).isEqualTo("QUARANTINE");
        assertThat(message.title()).contains("4");
        assertThat(message.body()).contains("HCM");
        assertThat(message.dedupeKey()).isEqualTo("quarantine-digest:" + warehouse + ":" + day);
    }

    @Test
    @DisplayName("nobody active to tell: logged, not thrown")
    void nobody() {
        when(identity.notificationRecipientsHolding(any(), any())).thenReturn(List.of());
        listener.on(new ReturnAwaitingRefund(returnId, "RMA-1", UUID.randomUUID(), "SO-1", UUID.randomUUID(),
                "GOODS_BACK", AT));
        verify(inbox, never()).post(anyList(), any());
    }
}
