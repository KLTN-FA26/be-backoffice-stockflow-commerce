package com.stockflow.inventory.internal.service;

import com.stockflow.inventory.internal.domain.StockEvent;
import com.stockflow.common.domain.AggregateRoot;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Drains the events an aggregate recorded and hands them to Spring.
 *
 * <p><b>What replaced the outbox.</b> In the microservices build every event went through an
 * {@code outbox_event} table plus a relay thread plus Kafka, for one reason: a database commit and
 * a Kafka publish are two systems and cannot be made atomic. Spring Modulith removes the problem
 * rather than working around it — {@code @ApplicationModuleListener} makes the listener
 * transactional and the framework persists the publication in the
 * {@code event_publication} table inside the <i>same</i> commit as the data change. Nothing is
 * lost, and {@code republish-outstanding-events-on-restart} redelivers whatever a crash
 * interrupted. Roughly 400 lines of hand-written outbox machinery deleted.</p>
 *
 * <p><b>What is published is the contract record, not the wrapper.</b> Listeners in other modules
 * write {@code void on(StockReserved event)} and never see {@code StockEvent}, which lives under
 * {@code inventory.internal} and is invisible to them by design.</p>
 */
@Component
class InventoryEventPublisher {

    private final ApplicationEventPublisher publisher;

    InventoryEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Publish and clear everything the aggregate recorded during this transaction.
     *
     * <p>Call this after {@code repository.save(...)}, still inside the transaction. Publishing
     * before the save would announce a change that a later constraint violation then rolls back.</p>
     */
    void publishEventsOf(AggregateRoot aggregate) {
        aggregate.pullDomainEvents().forEach(event -> {
            if (event instanceof StockEvent stockEvent) {
                publisher.publishEvent(stockEvent.payload());
            } else {
                publisher.publishEvent(event);
            }
        });
    }
}
