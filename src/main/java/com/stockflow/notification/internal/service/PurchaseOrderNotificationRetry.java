package com.stockflow.notification.internal.service;

import com.stockflow.common.events.PendingPublicationReader;
import com.stockflow.contracts.PurchaseOrderSent;
import com.stockflow.notification.internal.repository.PoRetryCursorRepository;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Bounded reads plus the existing proxied listener: no republishing or second queue. The loop is
 * {@link PublicationRedelivery}'s (QD-7); the purchase order keeps its durable cursor.
 */
@Component
class PurchaseOrderNotificationRetry {
    private final PendingPublicationReader publications;
    private final Clock clock;
    private final EventSerializer serializer;
    private final PublicationRedelivery<PurchaseOrderSent> redelivery;

    PurchaseOrderNotificationRetry(
            PendingPublicationReader publications,
            Clock clock,
            PoRetryCursorRepository cursor,
            PurchaseOrderNotificationListener listener,
            EventSerializer serializer) {
        this.publications = publications;
        this.clock = clock;
        this.serializer = serializer;
        this.redelivery = new PublicationRedelivery<>(PurchaseOrderSent.class,
                PurchaseOrderNotificationListener.class, "on", listener::on, new PublicationRedelivery.Cursor() {
                    @Override
                    public UUID load() {
                        return cursor.load();
                    }

                    @Override
                    public void save(UUID publicationId) {
                        cursor.save(publicationId);
                    }
                });
    }

    @Scheduled(fixedDelayString = "${stockflow.notification.po-retry-interval:PT1M}")
    @SchedulerLock(
            name = "notification.retryPurchaseOrders",
            lockAtMostFor = "PT4M",
            lockAtLeastFor = "PT30S")
    public void retry() {
        redelivery.redeliver(publications, serializer, clock.instant().minus(Duration.ofMinutes(1)), 50);
    }
}
