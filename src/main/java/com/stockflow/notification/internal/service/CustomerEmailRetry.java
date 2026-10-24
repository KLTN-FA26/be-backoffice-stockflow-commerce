package com.stockflow.notification.internal.service;

import com.stockflow.common.events.PendingPublicationReader;
import com.stockflow.common.events.PendingPublicationReader.PendingPublication;
import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderDelivered;
import com.stockflow.contracts.OrderPlaced;
import com.stockflow.contracts.OrderRemainderClosed;
import com.stockflow.contracts.ShipmentStatusChanged;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Sends again the customer messages whose last attempt failed in a way worth retrying, once their back-off has passed.
 *
 * <p>The same shape as {@link PurchaseOrderNotificationRetry}: the incomplete publications of
 * {@link OrderNotificationListener}'s handlers are read a bounded page at a time ({@link PendingPublicationReader}) and
 * handed to the listener's proxy, which delivers them on its own threads and, on success, lets Spring Modulith mark
 * them complete. No second queue, no republishing to other listeners.</p>
 *
 * <p>What it adds: a publication whose message is still inside its back-off ({@code next_attempt_at}, a provider's
 * {@code Retry-After}) is passed over, not dispatched to fail again. And the position in each event type's backlog is
 * kept between runs, so a head of messages waiting out a long back-off cannot starve the ones behind it. That position
 * is only fairness - kept in memory, lost on restart, which then starts from the oldest again.</p>
 */
@Component
class CustomerEmailRetry {

    private static final Logger log = LoggerFactory.getLogger(CustomerEmailRetry.class);

    static final int PAGE = 50;

    /** A publication younger than this is still being handled by its first dispatch. */
    private static final Duration SETTLING = Duration.ofMinutes(1);

    private final PendingPublicationReader publications;
    private final EventSerializer serializer;
    private final CustomerEmailLog deliveries;
    private final Clock clock;
    private final List<Route<?>> routes;
    private final Map<String, UUID> positions = new ConcurrentHashMap<>();

    CustomerEmailRetry(PendingPublicationReader publications, EventSerializer serializer,
                       OrderNotificationListener listener, CustomerEmailComposer composer,
                       CustomerEmailLog deliveries, Clock clock) {
        this.publications = publications;
        this.serializer = serializer;
        this.deliveries = deliveries;
        this.clock = clock;
        this.routes = List.of(
                route(OrderPlaced.class, composer::orderPlaced, listener::on),
                route(ShipmentStatusChanged.class, composer::shipmentChanged, listener::on),
                route(OrderDelivered.class, composer::orderDelivered, listener::on),
                route(OrderCancelled.class, composer::orderCancelled, listener::on),
                route(OrderRemainderClosed.class, composer::remainderClosed, listener::on));
    }

    /**
     * One event type the listener handles: how to read its publications back, which message it calls for, and the
     * listener method that sends it.
     */
    record Route<E>(Class<E> type, String listenerId, Function<E, Optional<CustomerMessage>> message,
                    Consumer<E> dispatch) {
    }

    private static <E> Route<E> route(Class<E> type, Function<E, Optional<CustomerMessage>> message,
                                      Consumer<E> dispatch) {
        try {
            var method = OrderNotificationListener.class.getMethod("on", type);
            String listenerId = new TransactionalApplicationListenerMethodAdapter(null,
                    OrderNotificationListener.class, method).getListenerId();
            return new Route<>(type, listenerId, message, dispatch);
        } catch (NoSuchMethodException missing) {
            throw new IllegalStateException("Customer e-mail retry target is missing for " + type.getSimpleName(),
                    missing);
        }
    }

    @Scheduled(fixedDelayString = "${stockflow.notification.email.customer.retry-interval:PT1M}",
            initialDelayString = "${stockflow.notification.email.customer.retry-interval:PT1M}")
    @SchedulerLock(name = "notification.retryCustomerEmails", lockAtMostFor = "PT4M", lockAtLeastFor = "PT30S")
    public void retry() {
        Instant now = clock.instant();
        for (Route<?> route : routes) {
            retry(route, now);
        }
    }

    private <E> void retry(Route<E> route, Instant now) {
        String type = route.type().getName();
        UUID after = positions.get(type);
        List<PendingPublication> page = new ArrayList<>(
                publications.page(type, route.listenerId(), now.minus(SETTLING), after, PAGE));
        if (page.isEmpty() && after != null) {
            page.addAll(publications.page(type, route.listenerId(), now.minus(SETTLING), null, PAGE));
        }
        if (page.isEmpty()) {
            positions.remove(type);
            return;
        }
        positions.put(type, page.getLast().id());
        int dispatched = 0;
        for (PendingPublication publication : page) {
            try {
                E event = serializer.deserialize(publication.serializedEvent(), route.type());
                Optional<CustomerMessage> message = route.message().apply(event);
                if (message.isPresent() && deliveries.waiting(message.get().reference(), now)) {
                    continue;
                }
                // Through the listener's proxy: asynchronous, and completed by Spring Modulith when it succeeds.
                route.dispatch().accept(event);
                dispatched++;
            } catch (RuntimeException failure) {
                log.warn("Customer e-mail retry could not dispatch publication {} ({})", publication.id(),
                        failure.getClass().getSimpleName());
            }
        }
        if (dispatched > 0) {
            log.info("Customer e-mail retry: {} of {} pending {} publication(s) dispatched", dispatched, page.size(),
                    route.type().getSimpleName());
        }
    }
}
