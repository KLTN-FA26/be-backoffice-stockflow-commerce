package com.stockflow.notification.internal.service;

import com.stockflow.contracts.InstallmentOverdue;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
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
 * WF4-OVERDUE-SALE (kltn-docs 15 §4.3 step 11): an overdue installment is told to the active holders of the permission
 * to hold an order by hand, by permission code - never by role name - once per person per installment.
 */
class InstallmentOverdueNotificationListenerTest {

    private static final UUID INSTALLMENT = UUID.randomUUID();
    private static final UUID ORDER = UUID.randomUUID();
    private static final InstallmentOverdue EVENT = new InstallmentOverdue(INSTALLMENT, UUID.randomUUID(),
            "HD-20261110-000001-1", ORDER, "SO-20261110-000001", UUID.randomUUID(), 2, LocalDate.of(2026, 11, 14),
            new BigDecimal("5000000"), "VND");

    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final Inbox inbox = mock(Inbox.class);
    private final InstallmentOverdueNotificationListener listener =
            new InstallmentOverdueNotificationListener(staff, inbox);

    @Test
    @DisplayName("tells every holder of the hold permission (business-wide), by permission code")
    @SuppressWarnings("unchecked")
    void tellsTheHoldersOfTheHoldPermission() {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        when(staff.activeHolders("sales-order-holds:UPDATE", null))
                .thenReturn(List.of(new StaffContact(one, "a@example.com"), new StaffContact(two, null)));

        listener.on(EVENT);

        ArgumentCaptor<Collection<UUID>> recipients = ArgumentCaptor.forClass(Collection.class);
        verify(inbox).post(recipients.capture(), any(InboxMessage.class));
        assertThat(recipients.getValue()).containsExactlyInAnyOrder(one, two);
    }

    @Test
    @DisplayName("nobody holds the permission: nothing posted, no failure")
    void nobodyToTell() {
        when(staff.activeHolders(any(), any())).thenReturn(List.of());

        listener.on(EVENT);

        verify(inbox, never()).post(any(), any());
    }

    @Test
    @DisplayName("the message is keyed by the installment, links to the order and carries only system text")
    void theMessage() {
        InboxMessage message = InstallmentOverdueNotificationListener.message(EVENT);

        assertThat(message.dedupeKey()).as("once per installment, for ever").isEqualTo("installment-overdue:" + INSTALLMENT);
        assertThat(message.category()).isEqualTo("PAYMENT_OVERDUE");
        assertThat(message.severity()).isEqualTo(InboxSeverity.WARN);
        assertThat(message.link()).isEqualTo("/admin/orders/" + ORDER);
        assertThat(message.sourceType()).isEqualTo("INSTALLMENT");
        assertThat(message.sourceId()).isEqualTo(INSTALLMENT.toString());
        assertThat(message.title()).contains("quá hạn").contains("HD-20261110-000001-1");
        assertThat(message.body()).contains("14/11/2026").contains("SO-20261110-000001").contains("treo tay")
                .contains("5.000.000 VND");
    }
}
