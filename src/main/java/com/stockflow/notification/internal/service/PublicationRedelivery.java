package com.stockflow.notification.internal.service;

import com.stockflow.common.events.PendingPublicationReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.core.EventSerializer;
import org.springframework.transaction.event.TransactionalApplicationListenerMethodAdapter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Hands the incomplete publications of one listener method back to it, a bounded page at a time (team plan QD-7: the
 * retry the purchase-order sender had, shared with production orders). The publications are read from the registry
 * ({@link PendingPublicationReader}) and passed to the listener's proxy, which delivers them on its own threads and, on
 * success, lets Spring Modulith mark them complete: no second queue, no republishing to other listeners.
 *
 * <p>A {@link Cursor} keeps the position in the backlog between runs, so a head of messages that keep failing cannot
 * starve the ones behind it; at the end the next run starts from the oldest again. The caller runs it from its own
 * {@code @Scheduled} method under {@code @SchedulerLock}, so one instance does it at a time.</p>
 *
 * @param <E> the event type the listener method takes
 */
final class PublicationRedelivery<E> {

    private static final Logger log = LoggerFactory.getLogger(PublicationRedelivery.class);

    /** Where the last run stopped. */
    interface Cursor {
        UUID load();

        void save(UUID publicationId);

        /** Kept in memory only: lost on restart, which then starts from the oldest again - fairness, not state. */
        static Cursor inMemory() {
            AtomicReference<UUID> position = new AtomicReference<>();
            return new Cursor() {
                @Override
                public UUID load() {
                    return position.get();
                }

                @Override
                public void save(UUID publicationId) {
                    position.set(publicationId);
                }
            };
        }
    }

    private final Class<E> type;
    private final String listenerId;
    private final Consumer<E> dispatch;
    private final Cursor cursor;

    /**
     * @param listener the listener class whose method {@code method(E)} the publications were registered for
     * @param dispatch that method, called on the listener's proxy
     * @throws IllegalStateException when the listener has no such method: a retry that names nothing would never run
     */
    PublicationRedelivery(Class<E> type, Class<?> listener, String method, Consumer<E> dispatch, Cursor cursor) {
        this.type = type;
        this.dispatch = dispatch;
        this.cursor = cursor;
        try {
            this.listenerId = new TransactionalApplicationListenerMethodAdapter(null, listener,
                    listener.getMethod(method, type)).getListenerId();
        } catch (NoSuchMethodException missing) {
            throw new IllegalStateException("Retry target is missing for " + type.getSimpleName(), missing);
        }
    }

    /**
     * One page of the publications older than {@code cutoff} after the cursor - or from the start when none is left
     * after it - each handed to the listener; a dispatch that fails is logged by publication id and class only.
     *
     * @return how many were handed over
     */
    int redeliver(PendingPublicationReader publications, EventSerializer serializer, Instant cutoff, int pageSize) {
        UUID previous = cursor.load();
        var page = new ArrayList<>(publications.page(type.getName(), listenerId, cutoff, previous, pageSize));
        if (page.isEmpty() && previous != null) {
            page.addAll(publications.page(type.getName(), listenerId, cutoff, null, pageSize));
        }
        if (page.isEmpty()) {
            return 0;
        }
        cursor.save(page.getLast().id());
        int dispatched = 0;
        for (var publication : page) {
            try {
                // CompletionRegisteringAdvisor marks this original publication completed on success; the listener's
                // proxy keeps its async, transaction and delivery-lock semantics.
                dispatch.accept(serializer.deserialize(publication.serializedEvent(), type));
                dispatched++;
            } catch (RuntimeException failure) {
                log.warn("{} retry dispatch failed for publication {} ({})", type.getSimpleName(), publication.id(),
                        failure.getClass().getSimpleName());
            }
        }
        return dispatched;
    }
}
