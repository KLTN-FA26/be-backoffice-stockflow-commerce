package com.stockflow.inventory.internal.domain;

import com.stockflow.contracts.StockDeducted;
import com.stockflow.contracts.StockReservationReleased;
import com.stockflow.contracts.StockReserved;
import com.stockflow.common.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Adapter between the flat records in {@code contracts} and the {@link DomainEvent} interface the
 * aggregate collects.
 *
 * <p>Two constraints pull in opposite directions. {@code contracts} records must stay flat and
 * import nothing, so they cannot implement {@code DomainEvent}. But {@link StockItem} wants to
 * register events with an id and a timestamp so they can be de-duplicated and ordered. A sealed
 * wrapper satisfies both: the payload published to listeners is still the plain contract record,
 * while the envelope carries the metadata.</p>
 *
 * <p>Sealed with one permitted subclass per contract, so
 * {@code switch (event) { case Reserved r -> ...; }} is exhaustive and adding a fourth event type
 * without handling it fails the build.</p>
 */
public sealed interface StockEvent extends DomainEvent {

    /** The flat contract record that actually gets published to other modules. */
    Object payload();

    record Reserved(UUID eventId, Instant occurredAt, StockReserved payload) implements StockEvent {
        public Reserved(StockReserved payload) {
            this(UUID.randomUUID(), Instant.now(), payload);
        }

        @Override
        public String aggregateId() {
            return payload.sku() + "@" + payload.locationCode();
        }
    }

    record Released(UUID eventId, Instant occurredAt, StockReservationReleased payload)
            implements StockEvent {
        public Released(StockReservationReleased payload) {
            this(UUID.randomUUID(), Instant.now(), payload);
        }

        @Override
        public String aggregateId() {
            return payload.sku();
        }
    }

    record Deducted(UUID eventId, Instant occurredAt, StockDeducted payload) implements StockEvent {
        public Deducted(StockDeducted payload) {
            this(UUID.randomUUID(), Instant.now(), payload);
        }

        @Override
        public String aggregateId() {
            return payload.sku() + "@" + payload.locationCode();
        }
    }
}
