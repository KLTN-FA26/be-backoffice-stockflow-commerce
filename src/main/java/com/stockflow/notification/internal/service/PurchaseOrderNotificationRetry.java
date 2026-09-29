package com.stockflow.notification.internal.service;

import com.stockflow.common.events.PendingPublicationReader;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.internal.repository.PoRetryCursorRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;

/** Bounded reads plus the existing proxied listener: no republishing or second queue. */
@Component
class PurchaseOrderNotificationRetry {
    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderNotificationRetry.class);
    private final PendingPublicationReader publications;
    private final Clock clock;
    private final PoRetryCursorRepository cursor;
    private final PurchaseOrderNotificationListener listener;
    private final EventSerializer serializer;
    private final String listenerId;

    PurchaseOrderNotificationRetry(PendingPublicationReader publications, Clock clock,
            PoRetryCursorRepository cursor, PurchaseOrderNotificationListener listener, EventSerializer serializer) {
        this.publications = publications;
        this.clock = clock;
        this.cursor = cursor;
        this.listener = listener;
        this.serializer = serializer;
        try {
            var method = PurchaseOrderNotificationListener.class.getMethod("on", PurchaseOrderSent.class);
            listenerId = new TransactionalApplicationListenerMethodAdapter(null, PurchaseOrderNotificationListener.class, method).getListenerId();
        } catch (NoSuchMethodException invalidListener) {
            throw new IllegalStateException("PO retry target is missing", invalidListener);
        }
    }

    @Scheduled(fixedDelayString = "${stockflow.notification.po-retry-interval:PT5M}")
    @SchedulerLock(name = "notification.retryPurchaseOrders", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void retry() {
        var cutoff = clock.instant().minus(Duration.ofMinutes(5));
        var previous = cursor.load();
        var selected = new ArrayList<>(publications.page(PurchaseOrderSent.class.getName(), listenerId, cutoff, previous, 50));
        if (selected.isEmpty() && previous != null) {
            selected.addAll(publications.page(PurchaseOrderSent.class.getName(), listenerId, cutoff, null, 50));
        }
        if (selected.isEmpty()) return;
        cursor.save(selected.getLast().id());
        for (var publication : selected) {
            try {
                // CompletionRegisteringAdvisor marks this original publication completed on success.
                // The injected listener proxy retains async, transaction and delivery-lock semantics.
                listener.on(serializer.deserialize(publication.serializedEvent(), PurchaseOrderSent.class));
            } catch (RuntimeException failure) {
                log.warn("PO retry dispatch failed for publication {} ({})", publication.id(), failure.getClass().getSimpleName());
            }
        }
    }
}
