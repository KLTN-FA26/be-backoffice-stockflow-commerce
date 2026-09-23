package com.stockflow.notification.internal.service;

import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.internal.repository.PoRetryCursorRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.UUID;

/** Retries the existing durable event registry, never creates a second notification queue. */
@Component
class PurchaseOrderNotificationRetry {
    private final IncompleteEventPublications publications;
    private final Clock clock;
    private final EventPublicationRegistry registry;
    private final PoRetryCursorRepository cursor;
    PurchaseOrderNotificationRetry(IncompleteEventPublications publications, Clock clock,
            EventPublicationRegistry registry, PoRetryCursorRepository cursor) {
        this.publications = publications;
        this.clock = clock;
        this.registry = registry;
        this.cursor = cursor;
    }

    @Scheduled(fixedDelayString = "${stockflow.notification.po-retry-interval:PT5M}")
    @SchedulerLock(name = "notification.retryPurchaseOrders", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void retry() {
        var cutoff = clock.instant().minus(Duration.ofMinutes(5));
        var eligible = registry.findIncompletePublications().stream()
                .filter(p -> p.getEvent() instanceof PurchaseOrderSent && p.getPublicationDate().isBefore(cutoff))
                .map(p -> p.getIdentifier()).sorted().toList();
        if (eligible.isEmpty()) return;
        UUID previous = cursor.load();
        int start = 0;
        if (previous != null) {
            while (start < eligible.size() && eligible.get(start).compareTo(previous) <= 0) start++;
            if (start == eligible.size()) start = 0;
        }
        var selected = new HashSet<UUID>();
        UUID last = null;
        for (int i = 0; i < Math.min(50, eligible.size()); i++) {
            last = eligible.get((start + i) % eligible.size());
            selected.add(last);
        }
        // Advance before dispatch: a poisonous publication must not pin the next batch.
        // Events remain durable; an interrupted batch is revisited on the next full circuit.
        cursor.save(last);
        publications.resubmitIncompletePublications(p -> selected.contains(p.getIdentifier()));
    }
}
