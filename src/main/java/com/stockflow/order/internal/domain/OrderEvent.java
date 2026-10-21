package com.stockflow.order.internal.domain;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderDelivered;
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

    /** A system cancellation refused because printing started: staff decide (decision 2026-10-17). */
    record CancellationReferred(UUID eventId, Instant occurredAt, com.stockflow.contracts.OrderCancellationReferred payload)
            implements OrderEvent {

        public CancellationReferred(com.stockflow.contracts.OrderCancellationReferred payload) {
            this(UUID.randomUUID(), Instant.now(), payload);
        }

        @Override
        public String aggregateId() {
            return payload.orderId().toString();
        }
    }

    /** Delivered by the carrier, as the coordinator recorded it: payment opens the receivable (SCRUM-431). */
    record Delivered(UUID eventId, Instant occurredAt, OrderDelivered payload) implements OrderEvent {

        public Delivered(OrderDelivered payload) {
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

    /** The rest of a partly fulfilled order was closed: payment refunds, fulfillment stops (G4-Q3). */
    record RemainderClosed(UUID eventId, Instant occurredAt, com.stockflow.contracts.OrderRemainderClosed payload)
            implements OrderEvent {

        public RemainderClosed(com.stockflow.contracts.OrderRemainderClosed payload) {
            this(UUID.randomUUID(), Instant.now(), payload);
        }

        @Override
        public String aggregateId() {
            return payload.orderId().toString();
        }
    }
}
