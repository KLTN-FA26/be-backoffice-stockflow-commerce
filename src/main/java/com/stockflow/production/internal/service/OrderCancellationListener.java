package com.stockflow.production.internal.service;

import com.stockflow.contracts.OrderCancelled;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * An order's cancellation reaches its production orders (SCRUM-401). After commit and in its own transaction:
 * the cancellation stands whatever production does; a failure here leaves the publication incomplete in Spring
 * Modulith's registry, to be retried.
 */
@Component("productionOrderCancellationListener")
class OrderCancellationListener {

    private final PrintRunService runs;

    OrderCancellationListener(PrintRunService runs) {
        this.runs = runs;
    }

    @ApplicationModuleListener
    public void on(OrderCancelled event) {
        runs.onOrderCancelled(event.orderId(), event.orderNumber());
    }
}
