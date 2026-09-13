package com.stockflow.product.internal.domain;

import com.stockflow.contracts.ProductApproved;
import com.stockflow.common.domain.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope giving {@code contracts} records the id and timestamp {@link DomainEvent} requires.
 * Same pattern as {@code OrderEvent}/{@code StockEvent} — see their javadoc for why the contract
 * records cannot implement the interface themselves.
 */
public sealed interface ProductEvent extends DomainEvent {

    Object payload();

    record Approved(UUID eventId, Instant occurredAt, ProductApproved payload) implements ProductEvent {

        public Approved(ProductApproved payload) {
            this(UUID.randomUUID(), Instant.now(), payload);
        }

        @Override
        public String aggregateId() {
            return payload.productId().toString();
        }
    }
}
