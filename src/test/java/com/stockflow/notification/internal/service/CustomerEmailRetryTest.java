package com.stockflow.notification.internal.service;

import com.stockflow.common.events.PendingPublicationReader;
import com.stockflow.common.events.PendingPublicationReader.PendingPublication;
import com.stockflow.contracts.OrderPlaced;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.core.EventSerializer;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.stockflow.notification.internal.service.EmailFixtures.placed;
import static com.stockflow.notification.internal.service.EmailFixtures.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The retry job: bounded pages of incomplete publications, each handed back to the listener unless still waiting. */
class CustomerEmailRetryTest {

    private static final Instant NOW = Instant.parse("2026-10-24T08:00:00Z");

    private final PendingPublicationReader reader = mock(PendingPublicationReader.class);
    private final EventSerializer serializer = mock(EventSerializer.class);
    private final OrderNotificationListener listener = mock(OrderNotificationListener.class);
    private final CustomerEmailLog log = mock(CustomerEmailLog.class);
    private final CustomerEmailComposer composer = new CustomerEmailComposer(properties());

    private CustomerEmailRetry retry() {
        return new CustomerEmailRetry(reader, serializer, listener, composer, log, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a message inside its back-off is passed over; the others go back to the listener's proxy")
    void dispatchesWhatIsDue() {
        OrderPlaced due = placed(UUID.randomUUID(), null);
        OrderPlaced waiting = placed(UUID.randomUUID(), null);
        Map<String, OrderPlaced> events = Map.of("due", due, "waiting", waiting);
        when(reader.page(anyString(), anyString(), any(), nullable(UUID.class), eq(CustomerEmailRetry.PAGE)))
                .thenReturn(List.of());
        when(reader.page(eq(OrderPlaced.class.getName()), anyString(), eq(NOW.minusSeconds(60)), nullable(UUID.class),
                eq(CustomerEmailRetry.PAGE)))
                .thenReturn(List.of(new PendingPublication(new UUID(0, 1), "due"),
                        new PendingPublication(new UUID(0, 2), "waiting")));
        when(serializer.deserialize(anyString(), eq(OrderPlaced.class)))
                .thenAnswer(call -> events.get(call.<String>getArgument(0)));
        when(log.waiting("customer-mail:order.placed:" + waiting.orderId(), NOW)).thenReturn(true);

        retry().retry();

        verify(listener).on(due);
        verify(listener, never()).on(waiting);
    }

    @Test
    @DisplayName("the position in a long backlog is kept between runs and wraps to the start at its end")
    void pagesThroughTheBacklog() {
        var pending = new ArrayList<PendingPublication>();
        for (int i = 1; i <= 120; i++) {
            pending.add(new PendingPublication(new UUID(0, i), Integer.toString(i)));
        }
        when(reader.page(anyString(), anyString(), any(), nullable(UUID.class), eq(CustomerEmailRetry.PAGE)))
                .thenReturn(List.of());
        when(reader.page(eq(OrderPlaced.class.getName()), anyString(), any(), nullable(UUID.class),
                eq(CustomerEmailRetry.PAGE))).thenAnswer(call -> {
                    UUID after = call.getArgument(3);
                    return pending.stream().filter(p -> after == null || p.id().compareTo(after) > 0)
                            .limit(CustomerEmailRetry.PAGE).toList();
                });
        var visited = new ArrayList<String>();
        when(serializer.deserialize(anyString(), eq(OrderPlaced.class))).thenAnswer(call -> {
            visited.add(call.getArgument(0));
            return placed(UUID.randomUUID(), null);
        });

        CustomerEmailRetry job = retry();
        for (int run = 0; run < 4; run++) {
            job.retry();
        }

        assertThat(visited.subList(0, 120)).doesNotHaveDuplicates().hasSize(120);
        assertThat(visited.subList(120, 170)).isEqualTo(visited.subList(0, 50));
    }

    @Test
    @DisplayName("nothing pending: nothing dispatched")
    void idle() {
        when(reader.page(anyString(), anyString(), any(), nullable(UUID.class), eq(CustomerEmailRetry.PAGE)))
                .thenReturn(List.of());
        retry().retry();
        verify(listener, never()).on(any(OrderPlaced.class));
    }
}
