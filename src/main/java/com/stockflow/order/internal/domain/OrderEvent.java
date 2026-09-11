package com.stockflow.order.internal.domain;

import com.stockflow.contracts.OrderPlaced;
import com.stockflow.common.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope giving {@code contracts} records the id and timestamp {@link DomainEvent} requires.
 * Same pattern as {@code StockEvent} in inventory — see its javadoc for why the contract records
 * cannot implement the interface themselves.
 */
public sealed interface OrderEvent extends DomainEvent {

    Object payload();

    record Placed(UUID eventId, Instant occurredAt, OrderPlaced payload) implements OrderEvent {

        public Placed(OrderPlaced payload) {
            this(UUID.randomUUID(), Instant.now(), payload);
        }

        @Override
        public String aggregateId() {
            return payload.orderId().toString();
        }
    }
}
