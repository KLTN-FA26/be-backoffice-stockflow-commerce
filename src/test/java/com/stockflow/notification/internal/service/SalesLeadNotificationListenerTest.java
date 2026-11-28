package com.stockflow.notification.internal.service;

import com.stockflow.contracts.SalesLeadAssigned;
import com.stockflow.contracts.SalesLeadSubmitted;
import com.stockflow.identity.api.SalesStaff;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.SalesAudiences;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Review finding C1, plan §10.7: a new lead tells the managers, an assignment tells the salesperson it was given to. */
class SalesLeadNotificationListenerTest {

    private final SalesStaff salesStaff = mock(SalesStaff.class);
    private final Inbox inbox = mock(Inbox.class);
    private final SalesLeadNotificationListener listener = new SalesLeadNotificationListener(
            new SalesNotices(mock(SalesAudiences.class), mock(StaffDirectory.class), salesStaff), inbox);

    private final UUID lead = UUID.randomUUID();
    private final Instant at = Instant.parse("2026-11-28T03:00:00Z");

    @SuppressWarnings("unchecked")
    private InboxMessage postedTo(List<UUID> who) {
        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(to.capture(), message.capture());
        assertThat(to.getValue()).containsExactlyElementsOf(who);
        return message.getValue();
    }

    @Test
    @DisplayName("C1 / §10.7: a new lead tells the Sales Managers, never every salesperson")
    void aNewLeadTellsTheManagers() {
        UUID manager = UUID.randomUUID();
        when(salesStaff.activeSalesManagers()).thenReturn(List.of(manager));

        listener.on(new SalesLeadSubmitted(lead, at));

        InboxMessage message = postedTo(List.of(manager));
        assertThat(message.title()).isEqualTo("Có khách tiềm năng mới từ website");
        assertThat(message.link()).isEqualTo("/admin/sales/leads");
        assertThat(message.sourceId()).isEqualTo(lead.toString());
        assertThat(message.dedupeKey()).isEqualTo("lead-submitted:" + lead);
    }

    @Test
    @DisplayName("C1 / §10.7: an assignment tells the salesperson it was given to")
    void anAssignmentTellsTheSalesperson() {
        UUID sales = UUID.randomUUID();

        listener.on(new SalesLeadAssigned(lead, sales, at));

        InboxMessage message = postedTo(List.of(sales));
        assertThat(message.title()).isEqualTo("Bạn được giao một khách tiềm năng mới");
        assertThat(message.dedupeKey()).isEqualTo("lead-assigned:" + lead + ":" + sales + ":" + at.toEpochMilli());
    }
}
