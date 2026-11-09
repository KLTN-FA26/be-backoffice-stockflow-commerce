package com.stockflow.notification.internal.service;

import com.stockflow.common.events.PendingPublicationReader;
import com.stockflow.contracts.ProductionOrderSampleApproved;
import com.stockflow.contracts.ProductionOrderSent;
import com.stockflow.contracts.ProductionOrderStopped;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Tries again the messages to producers whose last attempt failed in a way worth retrying (4A-3): the incomplete
 * publications of {@link ProductionOrderMailListener}'s three handlers, handed back to the listener's proxy a bounded
 * page at a time ({@link PublicationRedelivery}, the purchase-order retry's loop, QD-7). A message is tried at most five
 * times ({@link RecordedDelivery#RETRIED_FAILURES}); the fifth failure is final and the publication completes.
 *
 * <p>The warehouse managers' notice of the same {@code ProductionOrderSent}
 * ({@link ProductionOrderArrivalNotificationListener}) is retried here too: it has no delivery log, and its inbox
 * dedupe key makes a second delivery tell nobody twice.</p>
 *
 * <p>The position in each backlog is kept in memory: it is fairness, not state, and a restart starts from the oldest
 * again. One instance runs it at a time ({@code @SchedulerLock}).</p>
 */
@Component
class ProductionOrderMailRetry {

    private static final Logger log = LoggerFactory.getLogger(ProductionOrderMailRetry.class);

    /**
     * Publications per route and run. Each is handed to the listener's async executor and holds a connection while it
     * runs; a run queues at most four times this many (four routes), and the executor's core pool (8 threads,
     * {@code spring.task.execution}) bounds how many hold a connection at once.
     */
    static final int PAGE = 5;

    /** A publication younger than this is still being handled by its first dispatch. */
    private static final Duration SETTLING = Duration.ofMinutes(1);

    private final PendingPublicationReader publications;
    private final EventSerializer serializer;
    private final Clock clock;
    private final List<PublicationRedelivery<?>> routes;

    ProductionOrderMailRetry(PendingPublicationReader publications, EventSerializer serializer, Clock clock,
                             ProductionOrderMailListener listener, ProductionOrderArrivalNotificationListener arrivals) {
        this.publications = publications;
        this.serializer = serializer;
        this.clock = clock;
        Class<ProductionOrderMailListener> type = ProductionOrderMailListener.class;
        this.routes = List.of(
                new PublicationRedelivery<>(ProductionOrderSent.class, type, "on",
                        (ProductionOrderSent event) -> listener.on(event), PublicationRedelivery.Cursor.inMemory()),
                new PublicationRedelivery<>(ProductionOrderSampleApproved.class, type, "on",
                        (ProductionOrderSampleApproved event) -> listener.on(event),
                        PublicationRedelivery.Cursor.inMemory()),
                new PublicationRedelivery<>(ProductionOrderStopped.class, type, "on",
                        (ProductionOrderStopped event) -> listener.on(event), PublicationRedelivery.Cursor.inMemory()),
                new PublicationRedelivery<>(ProductionOrderSent.class, ProductionOrderArrivalNotificationListener.class,
                        "on", (ProductionOrderSent event) -> arrivals.on(event),
                        PublicationRedelivery.Cursor.inMemory()));
    }

    @Scheduled(fixedDelayString = "${stockflow.notification.production-order-retry-interval:PT1M}",
            initialDelayString = "${stockflow.notification.production-order-retry-interval:PT1M}")
    @SchedulerLock(name = "notification.retryProductionOrderMail", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void retry() {
        Instant cutoff = clock.instant().minus(SETTLING);
        int dispatched = 0;
        for (PublicationRedelivery<?> route : routes) {
            dispatched += route.redeliver(publications, serializer, cutoff, PAGE);
        }
        if (dispatched > 0) {
            log.info("Production order mail retry: {} pending publication(s) dispatched", dispatched);
        }
    }
}
