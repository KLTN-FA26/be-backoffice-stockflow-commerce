package com.stockflow.reporting.internal.service;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.OrderPlaced;
import com.stockflow.contracts.ReturnReplacementWithdrawn;
import com.stockflow.contracts.ReturnResolved;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the sales rollups current. {@code @ApplicationModuleListener} runs after the order's transaction commits, in
 * its own, so a failure here never fails checkout - and the event stays in the publication registry until this returns
 * normally, so it is delivered again after a crash. Delivery can therefore repeat; {@code SalesRollups} makes that harmless.
 *
 * <p>The handler methods are public: the transaction and the async hop are proxy advice.</p>
 */
@Component
class SalesRollupListener {

    private final SalesRollupService rollups;

    SalesRollupListener(SalesRollupService rollups) {
        this.rollups = rollups;
    }

    @ApplicationModuleListener
    public void on(OrderPlaced event) {
        rollups.orderPlaced(event.orderId());
    }

    @ApplicationModuleListener
    public void on(OrderCancelled event) {
        rollups.orderCancelled(event.orderId());
    }

    @ApplicationModuleListener
    public void on(ReturnResolved event) {
        rollups.returnResolved(event);
    }

    @ApplicationModuleListener
    public void on(ReturnReplacementWithdrawn event) {
        rollups.replacementWithdrawn(event);
    }
}
