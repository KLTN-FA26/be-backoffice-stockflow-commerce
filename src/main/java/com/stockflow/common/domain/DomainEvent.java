package com.stockflow.common.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Contract shared by every domain event in the system.
 * An event is something that already happened, so its name is always past tense:
 * StockReserved, OrderPlaced.
 */
public interface DomainEvent {

    UUID eventId();

    Instant occurredAt();

    /**
     * Id of the aggregate that produced the event.
     *
     * <p>Used for correlation in logs and traces. It is also what a message broker would partition
     * on if a module is ever extracted into its own service, which is why it is on the interface
     * rather than left to each event to invent.
     */
    String aggregateId();

    default String eventType() {
        return getClass().getSimpleName();
    }
}
