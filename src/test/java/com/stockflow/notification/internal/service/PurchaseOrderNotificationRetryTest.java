package com.stockflow.notification.internal.service;

import com.stockflow.common.events.PendingPublicationReader;
import com.stockflow.common.events.PendingPublicationReader.PendingPublication;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.internal.repository.PoRetryCursorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.core.EventSerializer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PurchaseOrderNotificationRetryTest {
    @Test void boundedPagesProgressAcrossRestartsAndWrap() {
        var reader = mock(PendingPublicationReader.class);
        var cursor = mock(PoRetryCursorRepository.class);
        var listener = mock(PurchaseOrderNotificationListener.class);
        var serializer = mock(EventSerializer.class);
        var clock = Clock.fixed(Instant.parse("2026-09-24T00:00:00Z"), ZoneOffset.UTC);
        var pending = new ArrayList<PendingPublication>();
        for (int i = 1; i <= 120; i++) pending.add(new PendingPublication(new UUID(0, i), Integer.toString(i)));
        var position = new AtomicReference<UUID>();
        when(cursor.load()).thenAnswer(call -> position.get());
        doAnswer(call -> { position.set(call.getArgument(0)); return null; }).when(cursor).save(any());
        when(reader.page(eq(PurchaseOrderSent.class.getName()), anyString(), any(), nullable(UUID.class), eq(50)))
                .thenAnswer(call -> {
                    UUID after = call.getArgument(3);
                    return pending.stream().filter(p -> after == null || p.id().compareTo(after) > 0).limit(50).toList();
                });
        var visited = new ArrayList<String>();
        when(serializer.deserialize(anyString(), eq(PurchaseOrderSent.class))).thenAnswer(call -> {
            visited.add(call.getArgument(0));
            return mock(PurchaseOrderSent.class);
        });
        for (int i = 0; i < 4; i++) new PurchaseOrderNotificationRetry(reader, clock, cursor, listener, serializer).retry();
        assertThat(visited.subList(0, 120)).doesNotHaveDuplicates().hasSize(120);
        assertThat(visited).hasSize(170);
        assertThat(visited.subList(120, 170)).isEqualTo(visited.subList(0, 50));
        verify(listener, times(170)).on(any());
    }

    @Test void emptyQueueDoesNotDispatchOrMoveCursor() {
        var reader = mock(PendingPublicationReader.class);
        var cursor = mock(PoRetryCursorRepository.class);
        var listener = mock(PurchaseOrderNotificationListener.class);
        when(reader.page(anyString(), anyString(), any(), nullable(UUID.class), eq(50))).thenReturn(List.of());
        new PurchaseOrderNotificationRetry(reader, Clock.systemUTC(), cursor, listener, mock(EventSerializer.class)).retry();
        verify(cursor, never()).save(any());
        verifyNoInteractions(listener);
    }
}
