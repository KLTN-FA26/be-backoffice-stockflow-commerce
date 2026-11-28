package com.stockflow.notification.internal.service;

import com.stockflow.contracts.InstallmentOverdue;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import com.stockflow.notification.api.SalesAudiences;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * WF4-OVERDUE-SALE (kltn-docs 15 §4.3 step 11): an overdue installment is told once per person per installment - since
 * 2026-11-28 to whoever looks after the customer (15 §4.1 step 4, BR-22): the owner or the cover, else the Sales
 * Managers marked "cần phân công"; no longer every holder of the permission to hold an order by hand.
 */
class InstallmentOverdueNotificationListenerTest {

    private static final UUID INSTALLMENT = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final InstallmentOverdue EVENT = new InstallmentOverdue(INSTALLMENT, UUID.randomUUID(),
            "HD-20261110-000001-1", ORDER, "SO-20261110-000001", UUID.randomUUID(), 2, LocalDate.of(2026, 11, 14),
            new BigDecimal("5000000"), "VND");

    private final SalesAudiences audiences = mock(SalesAudiences.class);
    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final Inbox inbox = mock(Inbox.class);
    private final InstallmentOverdueNotificationListener listener = new InstallmentOverdueNotificationListener(
            new SalesNotices(audiences, staff, mock(com.stockflow.identity.api.SalesStaff.class)), inbox);

    @Test
    @DisplayName("15 BR-22: the customer's owner (or cover) is told, unmarked; nobody is looked up by permission")
    @SuppressWarnings("unchecked")
    void tellsWhoeverLooksAfterTheCustomer() {
        UUID owner = UUID.randomUUID();
        when(audiences.ofCustomer(EVENT.customerId()))
                .thenReturn(Optional.of(new SalesAudience(List.of(owner), false)));

        listener.on(EVENT);

        ArgumentCaptor<Collection<UUID>> recipients = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(recipients.capture(), message.capture());
        assertThat(recipients.getValue()).containsExactly(owner);
        assertThat(message.getValue().body()).doesNotContain("Cần phân công");
        verify(staff, never()).activeHolders(any(), any());
    }

    @Test
    @DisplayName("15 §8 table: owner gone, nobody assigned - the Sales Managers are told, marked 'cần phân công'")
    @SuppressWarnings("unchecked")
    void managersWhenNobodyLooksAfterTheCustomer() {
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        when(audiences.ofCustomer(EVENT.customerId()))
                .thenReturn(Optional.of(new SalesAudience(List.of(m1, m2), true)));

        listener.on(EVENT);

        ArgumentCaptor<Collection<UUID>> recipients = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(recipients.capture(), message.capture());
        assertThat(recipients.getValue()).containsExactly(m1, m2);
        assertThat(message.getValue().body()).contains("Cần phân công").contains("SO-20261110-000001");
    }

    @Test
    @DisplayName("nobody to tell: nothing posted, no failure")
    void nobodyToTell() {
        when(audiences.ofCustomer(any())).thenReturn(Optional.empty());

        listener.on(EVENT);

        verify(inbox, never()).post(any(), any());
    }

    @Test
    @DisplayName("the message is keyed by the installment and its due date, links to the order and carries only system "
            + "text")
    void theMessage() {
        InboxMessage message = InstallmentOverdueNotificationListener.message(EVENT);

        assertThat(message.dedupeKey()).as("WF-05 BE-49: once per installment and due date")
                .isEqualTo("installment-overdue:" + INSTALLMENT + ":2026-11-14");
        assertThat(message.category()).isEqualTo("PAYMENT_OVERDUE");
        assertThat(message.severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(message.link()).isEqualTo("/admin/orders/" + ORDER);
        assertThat(message.sourceType()).isEqualTo("INSTALLMENT");
        assertThat(message.sourceId()).isEqualTo(INSTALLMENT.toString());
        assertThat(message.title()).contains("quá hạn").contains("HD-20261110-000001-1");
        assertThat(message.body()).contains("14/11/2026").contains("SO-20261110-000001").contains("treo tay")
                .contains("5.000.000 VND");
    }

    @Test
    @DisplayName("BE-49: the same installment overdue again on a new due date is a new message; the same date is not")
    void newDueDateNewMessage() {
        InstallmentOverdue moved = new InstallmentOverdue(EVENT.installmentId(), EVENT.contractId(),
                EVENT.contractNumber(), EVENT.orderId(), EVENT.orderNumber(), EVENT.customerId(), EVENT.seq(),
                EVENT.dueDate().plusDays(10), EVENT.outstanding(), EVENT.currency());

        assertThat(InstallmentOverdueNotificationListener.message(moved).dedupeKey())
                .isNotEqualTo(InstallmentOverdueNotificationListener.message(EVENT).dedupeKey());
        assertThat(InstallmentOverdueNotificationListener.message(EVENT).dedupeKey())
                .isEqualTo(InstallmentOverdueNotificationListener.message(EVENT).dedupeKey());
    }
}
