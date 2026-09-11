package com.stockflow.order.internal.service;

import com.stockflow.order.internal.domain.OrderEvent;
import com.stockflow.common.domain.AggregateRoot;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Drains the order aggregate's recorded events and publishes their contract payloads.
 *
 * <p>Deliberately duplicated per module rather than pulled into {@code common}. It is fifteen
 * lines, and a shared version would have to know about every module's event wrapper — turning a
 * neutral shared kernel into a hub that depends on everything. Small duplication beats a
 * dependency magnet.</p>
 */
@Component
class OrderEventPublisher {

    private final ApplicationEventPublisher publisher;

    OrderEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    void publishEventsOf(AggregateRoot aggregate) {
        aggregate.pullDomainEvents().forEach(event -> {
            if (event instanceof OrderEvent orderEvent) {
                publisher.publishEvent(orderEvent.payload());
            } else {
                publisher.publishEvent(event);
            }
        });
    }
}
