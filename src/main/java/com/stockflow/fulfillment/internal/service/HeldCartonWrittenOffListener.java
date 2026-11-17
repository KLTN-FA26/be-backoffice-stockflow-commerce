package com.stockflow.fulfillment.internal.service;

import com.stockflow.contracts.StockHeldCartonWrittenOff;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * kltn-docs 20 §4.2 step 8, 07 §4.4: a count's shortage of a carton an order still holds is handled as a shortage. When
 * the approval writes the carton off, inventory gives the hold up and announces it ({@link StockHeldCartonWrittenOff});
 * here the pick line that stood on it turns SHORT and the order is held for stock ({@code INVENTORY}), where the
 * warehouse manager reallocates it (or Sale decides) - the path a picker's short report takes, which is why a line
 * already SHORT is left alone.
 *
 * <p>After the commit and asynchronous ({@code @ApplicationModuleListener}), so the approval, which holds the SKU lock
 * and the carton, never waits for the pick and the order, which every other writer locks first: the lock order stays
 * pick, then order, then stock rows. Idempotent, as the event is delivered at least once
 * ({@link PickLists#shortenForWrittenOffCarton}).</p>
 */
@Component
class HeldCartonWrittenOffListener {

    private final PickLists pickLists;

    HeldCartonWrittenOffListener(PickLists pickLists) {
        this.pickLists = pickLists;
    }

    @ApplicationModuleListener
    public void on(StockHeldCartonWrittenOff event) {
        pickLists.shortenForWrittenOffCarton(event.orderId(), event.reservationId(), event.decidedBy());
    }
}
