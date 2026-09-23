package com.stockflow.notification.internal.service;

import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.internal.repository.PoRetryCursorRepository;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.events.EventPublication;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.TargetEventPublication;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PurchaseOrderNotificationRetryTest {
    private final Instant now = Instant.parse("2026-09-24T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final EventPublicationRegistry registry = mock(EventPublicationRegistry.class);
    private final IncompleteEventPublications publications = mock(IncompleteEventPublications.class);
    private final PoRetryCursorRepository cursor = mock(PoRetryCursorRepository.class);

    private TargetEventPublication publication(int id, boolean po, Instant date) {
        var publication = mock(TargetEventPublication.class);
        when(publication.getIdentifier()).thenReturn(new UUID(0, id));
        when(publication.getEvent()).thenReturn(po ? mock(PurchaseOrderSent.class) : "other event");
        when(publication.getPublicationDate()).thenReturn(date);
        return publication;
    }

    @Test void persistentFailuresDoNotStarveLaterEventsEvenAfterRestart() {
        var pending = new ArrayList<TargetEventPublication>();
        for (int i = 1; i <= 120; i++) pending.add(publication(i, true, now.minusSeconds(600)));
        pending.add(publication(121, false, now.minusSeconds(600)));
        pending.add(publication(122, true, now.minusSeconds(60)));
        when(registry.findIncompletePublications()).thenReturn(pending);
        var position = new AtomicReference<UUID>();
        when(cursor.load()).thenAnswer(call -> position.get());
        doAnswer(call -> { position.set(call.getArgument(0)); return null; }).when(cursor).save(any());
        var batches = new ArrayList<List<UUID>>();
        doAnswer(call -> {
            Predicate<EventPublication> filter = call.getArgument(0);
            batches.add(pending.stream().filter(filter).map(EventPublication::getIdentifier).toList());
            return null; // Every event stays incomplete, as if its supplier is permanently down.
        }).when(publications).resubmitIncompletePublications(any());
        for (int i = 0; i < 3; i++) {
            new PurchaseOrderNotificationRetry(publications, clock, registry, cursor).retry();
        }
        assertThat(batches).allSatisfy(batch -> assertThat(batch).hasSize(50));
        var reached = new HashSet<UUID>();
        batches.forEach(reached::addAll);
        assertThat(reached).hasSize(120).doesNotContain(new UUID(0, 121), new UUID(0, 122));
        assertThat(batches.get(0)).doesNotContainAnyElementsOf(batches.get(1));
    }

    @Test void removedCursorAndSmallQueueWrapWithoutDuplicates() {
        when(cursor.load()).thenReturn(new UUID(0, 90));
        var pending = List.of(publication(1, true, now.minusSeconds(600)), publication(2, true, now.minusSeconds(600)));
        when(registry.findIncompletePublications()).thenReturn(pending);
        new PurchaseOrderNotificationRetry(publications, clock, registry, cursor).retry();
        verify(cursor).save(new UUID(0, 2));
        verify(publications).resubmitIncompletePublications(any());
    }

    @Test void emptyQueueDoesNotDispatchOrMoveCursor() {
        when(registry.findIncompletePublications()).thenReturn(List.of());
        new PurchaseOrderNotificationRetry(publications, clock, registry, cursor).retry();
        verifyNoInteractions(cursor, publications);
    }
}
