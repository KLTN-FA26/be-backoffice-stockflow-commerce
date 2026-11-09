package com.stockflow.notification.internal.service;

import com.stockflow.common.events.PendingPublicationReader;
import com.stockflow.common.events.PendingPublicationReader.PendingPublication;
import com.stockflow.contracts.ProductionOrderSampleApproved;
import com.stockflow.contracts.ProductionOrderSent;
import com.stockflow.contracts.ProductionOrderStopped;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The retry of messages to producers (4A-3): each incomplete publication of the three handlers - and of the warehouse
 * managers' notice - is handed back to its handler, through the shared loop of the purchase-order retry (QD-7); one
 * instance at a time.
 */
class ProductionOrderMailRetryTest {

    private static final Instant NOW = Instant.parse("2026-11-10T03:00:00Z");

    private final ProductionOrderArrivalNotificationListener arrivals =
            mock(ProductionOrderArrivalNotificationListener.class);

    @Test
    @DisplayName("every pending message, of every kind, goes back to its handler")
    void redelivers() {
        var reader = mock(PendingPublicationReader.class);
        var serializer = mock(EventSerializer.class);
        var listener = mock(ProductionOrderMailListener.class);
        UUID id = UUID.randomUUID();
        var sent = new ProductionOrderSent(UUID.randomUUID(), id, "PRD-1", UUID.randomUUID(), null, 1, null, NOW);
        var sample = new ProductionOrderSampleApproved(UUID.randomUUID(), id, "PRD-1", NOW);
        var stopped = new ProductionOrderStopped(UUID.randomUUID(), id, "PRD-1", "CANCELLED", NOW);
        when(reader.page(eq(ProductionOrderSent.class.getName()), anyString(), any(), nullable(UUID.class), eq(ProductionOrderMailRetry.PAGE)))
                .thenReturn(List.of(new PendingPublication(new UUID(0, 1), "sent")));
        when(reader.page(eq(ProductionOrderSampleApproved.class.getName()), anyString(), any(), nullable(UUID.class),
                eq(ProductionOrderMailRetry.PAGE))).thenReturn(List.of(new PendingPublication(new UUID(0, 2), "sample")));
        when(reader.page(eq(ProductionOrderStopped.class.getName()), anyString(), any(), nullable(UUID.class), eq(ProductionOrderMailRetry.PAGE)))
                .thenReturn(List.of(new PendingPublication(new UUID(0, 3), "stopped")));
        when(serializer.deserialize("sent", ProductionOrderSent.class)).thenReturn(sent);
        when(serializer.deserialize("sample", ProductionOrderSampleApproved.class)).thenReturn(sample);
        when(serializer.deserialize("stopped", ProductionOrderStopped.class)).thenReturn(stopped);

        new ProductionOrderMailRetry(reader, serializer, Clock.fixed(NOW, ZoneOffset.UTC), listener, arrivals).retry();

        verify(listener).on(sent);
        verify(listener).on(sample);
        verify(listener).on(stopped);
        verify(arrivals).on(sent);
        verify(reader, org.mockito.Mockito.times(2)).page(eq(ProductionOrderSent.class.getName()), anyString(),
                eq(NOW.minusSeconds(60)), nullable(UUID.class), eq(ProductionOrderMailRetry.PAGE));
    }

    @Test
    @DisplayName("nothing pending: nothing dispatched")
    void idle() {
        var reader = mock(PendingPublicationReader.class);
        var listener = mock(ProductionOrderMailListener.class);
        when(reader.page(anyString(), anyString(), any(), nullable(UUID.class), eq(ProductionOrderMailRetry.PAGE))).thenReturn(List.of());

        new ProductionOrderMailRetry(reader, mock(EventSerializer.class), Clock.systemUTC(), listener, arrivals).retry();

        verifyNoInteractions(listener);
    }

    @Test
    @DisplayName("scheduled under a ShedLock, so one instance retries at a time")
    void locked() throws Exception {
        var retry = ProductionOrderMailRetry.class.getMethod("retry");
        assertThat(retry.getAnnotation(Scheduled.class)).isNotNull();
        assertThat(retry.getAnnotation(SchedulerLock.class).name()).isEqualTo("notification.retryProductionOrderMail");
    }
}
