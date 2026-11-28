package com.stockflow.notification.internal.service;

import com.stockflow.contracts.ReturnAwaitingRefund;
import com.stockflow.contracts.ReturnReceived;
import com.stockflow.contracts.ReturnedCartonsInQuarantine;
import com.stockflow.contracts.ReturnedGoodsReceived;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.SalesAudiences;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
 * to the people who may act on it, in the words of the {@code _vi} bundle. On Sales' side, since 2026-11-28, whoever
 * looks after the customer (17 §4.10, 00 §6): the owner or the cover, else the Sales Managers marked "cần phân công".
 */
class ReturnStaffNotificationListenerTest {

    private static final Instant AT = Instant.parse("2026-11-23T03:00:00Z");

    private final IdentityService identity = mock(IdentityService.class);
    private final Inbox inbox = mock(Inbox.class);
    private final NotificationSender sender = mock(NotificationSender.class);
    private final SalesAudiences audiences = mock(SalesAudiences.class);
    private final ReturnStaffNotificationListener listener = new ReturnStaffNotificationListener(sender,
            new StaffDirectory(identity), new SalesNotices(audiences, new StaffDirectory(identity),
            mock(com.stockflow.identity.api.SalesStaff.class)), new StaffNotices(inbox));

    private void looksAfterEveryCustomer(UUID who, String email, boolean needsAssignment) {
        when(audiences.ofCustomer(any())).thenReturn(java.util.Optional.of(new SalesAudience(List.of(who),
                needsAssignment)));
        when(identity.user(who)).thenReturn(new com.stockflow.identity.api.StaffUser(who, "u", email, "Name", "ACTIVE",
                List.of(), List.of(), false, null, null, null, null, null, 0));
    }

    private final UUID sales = UUID.randomUUID();
    private final UUID returnId = UUID.randomUUID();

    private InboxMessage posted(List<UUID> to) {
        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(eq(to), message.capture());
        return message.getValue();
    }

    @Test
    @DisplayName("a return waits for its refund: the customer's owner is told, keyed by return and moment, by mail too")
    void awaitingProposal() {
        looksAfterEveryCustomer(sales, "sales@example.com", false);
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
        verify(identity, never()).notificationRecipientsHolding(eq("sales-rma-resolutions:UPDATE"), any());
    }

    @Test
    @DisplayName("§10.2: a return of a customer with no active owner reaches the Sales Managers, marked 'cần phân công'")
    void awaitingProposalWithNoOwner() {
        UUID manager = UUID.randomUUID();
        looksAfterEveryCustomer(manager, "manager@example.com", true);
        when(inbox.post(any(), any())).thenReturn(1);

        listener.on(new ReturnAwaitingRefund(returnId, "RMA-20261123-0001", UUID.randomUUID(), "SO-0042",
                UUID.randomUUID(), "GOODS_COMPLETE", AT));

        assertThat(posted(List.of(manager)).body()).contains("Cần phân công");
    }

    @Test
    @DisplayName("a parcel with a discrepancy is reported to Sales with its receipt; one without is not")
    void discrepancy() {
        looksAfterEveryCustomer(sales, null, false);
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
    @DisplayName("the daily quarantine digest goes to who approves a write-off in that warehouse (its managers), once "
            + "a day; none when nothing waits")
    void quarantineDigest() {
        UUID warehouse = UUID.randomUUID();
        UUID manager = UUID.randomUUID();
        when(identity.notificationRecipientsHolding("inventory-stock-adjustments:APPROVE", warehouse))
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
