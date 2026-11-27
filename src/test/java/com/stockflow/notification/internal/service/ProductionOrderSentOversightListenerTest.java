package com.stockflow.notification.internal.service;

import com.stockflow.contracts.ProductionOrderSent;
import com.stockflow.identity.api.StaffContact;
import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.notification.api.InboxSeverity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Sales Manager hears, in-app only, that a production order was sent; once per order, never twice. */
class ProductionOrderSentOversightListenerTest {

    private static final UUID ORDER = UUID.fromString("0192f000-0000-7000-8000-0000000000b2");

    private final StaffDirectory staff = mock(StaffDirectory.class);
    private final Inbox inbox = mock(Inbox.class);
    private final ProductionOrderSentOversightListener listener =
            new ProductionOrderSentOversightListener(staff, inbox);

    private static ProductionOrderSent sent() {
        return new ProductionOrderSent(UUID.randomUUID(), ORDER, "PRD-20261110-0003", UUID.randomUUID(),
                LocalDate.of(2026, 11, 30), 24, BigDecimal.ONE, Instant.parse("2026-11-10T03:00:00Z"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tellsEveryActiveHolderOfTheAudience() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(staff.activeHolders("production-order-sends:READ", null)).thenReturn(List.of(
                new StaffContact(first, "a@stockflow.vn"), new StaffContact(second, null)));
        when(inbox.post(any(), any())).thenReturn(2);

        listener.on(sent());

        var recipients = ArgumentCaptor.forClass(List.class);
        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox).post(recipients.capture(), message.capture());
        assertThat(recipients.getValue()).containsExactly(first, second);
        assertThat(message.getValue().title()).contains("PRD-20261110-0003");
        assertThat(message.getValue().body()).contains("24").contains("30/11/2026");
        assertThat(message.getValue().severity()).isEqualTo(InboxSeverity.INFO);
        assertThat(message.getValue().link()).isEqualTo("/production/orders/" + ORDER);
        assertThat(message.getValue().dedupeKey()).isEqualTo("production-order-sent-oversight:" + ORDER);
    }

    @Test
    void nobodyToTellPostsNothing() {
        when(staff.activeHolders("production-order-sends:READ", null)).thenReturn(List.of());

        listener.on(sent());

        verify(inbox, never()).post(any(), any());
    }

    @Test
    void aRedeliveredEventUsesTheSameKey() {
        when(staff.activeHolders(eq("production-order-sends:READ"), any()))
                .thenReturn(List.of(new StaffContact(UUID.randomUUID(), null)));
        when(inbox.post(any(), any())).thenReturn(1);

        listener.on(sent());
        listener.on(sent());

        var message = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox, org.mockito.Mockito.times(2)).post(any(), message.capture());
        assertThat(message.getAllValues()).extracting(InboxMessage::dedupeKey).containsOnly(
                "production-order-sent-oversight:" + ORDER);
    }
}
