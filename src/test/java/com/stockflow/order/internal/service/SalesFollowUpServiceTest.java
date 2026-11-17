package com.stockflow.order.internal.service;

import com.stockflow.notification.api.Inbox;
import com.stockflow.notification.api.InboxMessage;
import com.stockflow.order.internal.domain.SalesFollowUps;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A10 (kltn-docs 14 §6): the salesperson who sent a contract is told, once each, of a contract unsigned and of an order
 * unpaid after the configured days.
 */
class SalesFollowUpServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");

    private final SalesFollowUps followUps = mock(SalesFollowUps.class);
    private final Inbox inbox = mock(Inbox.class);

    @Test
    @DisplayName("past N days (3 by default): one notice per contract and per order, to its salesperson, keyed once")
    void remindsTheSalespersonOnce() {
        UUID sales = UUID.randomUUID();
        UUID contract = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        UUID signedContract = UUID.randomUUID();
        when(followUps.unsignedSince(eq(NOW.minus(Duration.ofDays(3))), anyInt())).thenReturn(List.of(
                new SalesFollowUps.UnsignedContract(contract, "HD-20261108-0001-1", UUID.randomUUID(),
                        "SO-20261108-0001", sales, NOW.minus(Duration.ofDays(5)))));
        when(followUps.unpaidSince(eq(NOW.minus(Duration.ofDays(2))), anyInt())).thenReturn(List.of(
                new SalesFollowUps.UnpaidOrder(order, "SO-20261109-0002", signedContract, "HD-20261109-0002-1",
                        sales, NOW.minus(Duration.ofDays(2)))));
        when(inbox.post(any(), any())).thenReturn(1);
        SalesFollowUpService service = new SalesFollowUpService(followUps, inbox, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofDays(3), Duration.ofDays(2));

        SalesFollowUpService.Reminded reminded = service.remind();

        assertThat(reminded).isEqualTo(new SalesFollowUpService.Reminded(1, 1, 2));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> to = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<InboxMessage> told = ArgumentCaptor.forClass(InboxMessage.class);
        verify(inbox, times(2)).post(to.capture(), told.capture());
        assertThat(to.getAllValues()).allSatisfy(recipients -> assertThat(recipients).containsExactly(sales));
        assertThat(told.getAllValues()).extracting(InboxMessage::dedupeKey)
                .containsExactly("contract-unsigned:" + contract, "order-unpaid:" + signedContract);
        assertThat(told.getAllValues()).extracting(InboxMessage::category)
                .containsOnly(SalesFollowUpService.CATEGORY);
        assertThat(told.getAllValues().getFirst().title()).contains("HD-20261108-0001-1", "3");
        assertThat(told.getAllValues().get(1).title()).contains("SO-20261109-0002", "2");
        assertThat(told.getAllValues().get(1).link()).isEqualTo("/admin/orders/" + order);
    }

    @Test
    @DisplayName("a zero or negative wait is a configuration error, refused at startup")
    void waitsArePositive() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThatThrownBy(() -> new SalesFollowUpService(followUps, inbox, clock, Duration.ZERO, Duration.ofDays(3)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SalesFollowUpService(followUps, inbox, clock, Duration.ofDays(3),
                Duration.ofDays(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
