package com.stockflow.order.internal.domain;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderLinesReleasedForProduction;
import com.stockflow.contracts.OrderPlaced;
import com.stockflow.contracts.OrderReleased;
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

    /** Released with print lines: production makes them (SCRUM-423). */
    record LinesReleased(UUID eventId, Instant occurredAt, OrderLinesReleasedForProduction payload) implements OrderEvent {

        public LinesReleased(OrderLinesReleasedForProduction payload) {
            this(UUID.randomUUID(), Instant.now(), payload);
        }

        @Override
        public String aggregateId() {
            return payload.orderId().toString();
        }
    }

    /** Cancelled, on any path: payment, fulfillment and production follow (SCRUM-460). */
    record Cancelled(UUID eventId, Instant occurredAt, OrderCancelled payload) implements OrderEvent {

        public Cancelled(OrderCancelled payload) {
            this(UUID.randomUUID(), Instant.now(), payload);
        }

        @Override
        public String aggregateId() {
            return payload.orderId().toString();
        }
    }

    /** Ready to fulfil from its warehouse: released with nothing to print, or production finished. */
    record Released(UUID eventId, Instant occurredAt, OrderReleased payload) implements OrderEvent {

        public Released(OrderReleased payload) {
            this(UUID.randomUUID(), Instant.now(), payload);
        }

        @Override
        public String aggregateId() {
            return payload.orderId().toString();
        }
    }
}
