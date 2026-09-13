package com.stockflow.product.internal.service;

import com.stockflow.product.internal.domain.ProductEvent;
import com.stockflow.common.domain.AggregateRoot;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Drains the events {@link com.stockflow.product.internal.domain.Product} recorded and hands them
 * to Spring. Same shape as {@code InventoryEventPublisher}/{@code OrderEventPublisher} — see their
 * javadoc for why this replaces a hand-written outbox.
 */
@Component
class ProductEventPublisher {

    private final ApplicationEventPublisher publisher;

    ProductEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /** Publish and clear everything the aggregate recorded during this transaction. */
    void publishEventsOf(AggregateRoot aggregate) {
        aggregate.pullDomainEvents().forEach(event -> {
            if (event instanceof ProductEvent productEvent) {
                publisher.publishEvent(productEvent.payload());
            } else {
                publisher.publishEvent(event);
            }
        });
    }
}
