package com.stockflow.production.internal.service;

import com.stockflow.contracts.OrderLinesReleasedForProduction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Turns a release into production orders (SCRUM-405). Asynchronous and after commit, in its own
 * transaction: the release stands whatever production does with it, and a failure here leaves the
 * publication incomplete in Spring Modulith's registry to be retried, instead of rolling back the
 * coordinator's release. Rethrowing is what keeps it incomplete — swallowing the exception would mark
 * an event handled that created nothing.
 */
@Component("productionOrderReleaseListener")
class OrderReleaseListener {

    private static final Logger log = LoggerFactory.getLogger(OrderReleaseListener.class);

    private final OrderProductionService production;

    OrderReleaseListener(OrderProductionService production) {
        this.production = production;
    }

    @ApplicationModuleListener
    public void on(OrderLinesReleasedForProduction event) {
        try {
            production.createFor(event);
        } catch (RuntimeException refused) {
            log.error("No production orders created for released order {}: {}", event.orderNumber(),
                    refused.getMessage());
            throw refused;
        }
    }
}
