package com.stockflow.notification.internal.service;

import com.stockflow.contracts.LeadFormFromExistingCustomer;
import com.stockflow.identity.api.SalesStaff;
import com.stockflow.notification.api.CustomerContact;
import com.stockflow.notification.api.CustomerDirectory;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.SalesAudiences;
import com.stockflow.notification.api.SalesAudiences.SalesAudience;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * kltn-docs 18 §4.6 step 27, 00 §6: the lead form naming an existing customer's tax code tells whoever looks after
 * that customer - the owner or the cover, else the Sales Managers marked "cần phân công" - never every salesperson.
 */
class LeadFormFromCustomerNotificationListenerTest {

    private final SalesAudiences audiences = mock(SalesAudiences.class);
    private final CustomerDirectory customers = mock(CustomerDirectory.class);
    private final Inbox inbox = mock(Inbox.class);
    private final LeadFormFromCustomerNotificationListener listener = new LeadFormFromCustomerNotificationListener(
            new SalesNotices(audiences, mock(StaffDirectory.class), mock(SalesStaff.class)), customers, inbox);

    private final UUID customer = UUID.randomUUID();
    private final LeadFormFromExistingCustomer event = new LeadFormFromExistingCustomer(customer,
            Instant.parse("2026-11-28T03:00:00Z"));

    @SuppressWarnings("unchecked")
    private InboxMessage postedTo(List<UUID> who) {
        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(to.capture(), message.capture());
        assertThat(to.getValue()).containsExactlyElementsOf(who);
        return message.getValue();
    }

    @Test
    @DisplayName("18 §4.6 step 27, finding S4: the customer's owner is told, by the customer's name, once per business day")
    void theOwnerIsTold() {
        UUID owner = UUID.randomUUID();
        when(audiences.ofCustomer(customer)).thenReturn(Optional.of(new SalesAudience(List.of(owner), false)));
        when(customers.contact(customer)).thenReturn(Optional.of(new CustomerContact(customer, "Cong ty In Ly A",
                "a@example.com")));

        listener.on(event);

        InboxMessage message = postedTo(List.of(owner));
        assertThat(message.title()).isEqualTo("Khách Cong ty In Ly A vừa gửi form yêu cầu tư vấn từ website");
        assertThat(message.body()).doesNotContain("Cần phân công");
        assertThat(message.link()).isEqualTo("/admin/customers/" + customer);
        assertThat(message.dedupeKey()).isEqualTo("lead-form-existing:" + customer + ":2026-11-28");
        assertThat(LeadFormFromCustomerNotificationListener.message(new LeadFormFromExistingCustomer(customer,
                Instant.parse("2026-11-28T16:59:00Z")), "A").dedupeKey())
                .as("S4: a second form later the same Vietnamese business day shares the key")
                .isEqualTo(message.dedupeKey());
        assertThat(LeadFormFromCustomerNotificationListener.message(new LeadFormFromExistingCustomer(customer,
                Instant.parse("2026-11-28T17:00:00Z")), "A").dedupeKey())
                .as("the next business day (00:00 in Vietnam) is a new notice").endsWith(":2026-11-29");
    }

    @Test
    @DisplayName("D6: no active owner - the Sales Managers, marked 'cần phân công'")
    void theManagersMarked() {
        UUID manager = UUID.randomUUID();
        when(audiences.ofCustomer(customer)).thenReturn(Optional.of(new SalesAudience(List.of(manager), true)));

        listener.on(event);

        assertThat(postedTo(List.of(manager)).body()).contains("Cần phân công");
    }

    @Test
    void nobodyToTell() {
        when(audiences.ofCustomer(customer)).thenReturn(Optional.empty());

        listener.on(event);

        verify(inbox, never()).post(any(), any());
    }
}
