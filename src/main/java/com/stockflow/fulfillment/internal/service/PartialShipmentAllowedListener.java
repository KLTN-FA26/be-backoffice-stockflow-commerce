package com.stockflow.fulfillment.internal.service;

import com.stockflow.contracts.OrderPartialShipmentAllowed;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Sales allowed an order to ship in parts (kltn-docs 17 §4.5): an order held only because a carton was removed at the
 * check station is released from that hold at once, so the warehouse goes on without asking Sales a second time
 * (owner decision WF4-F2, 2026-11-28; {@link PickLists#liftHoldForRemovedCartons}).
 *
 * <p>After the commit, not in the decision's transaction: the check station takes the pick's lock before the order's, so
 * taking the order's first and the pick's after would invert the lock order. Idempotent: a repeated delivery finds the
 * order no longer held and changes nothing.</p>
 */
@Component("fulfillmentPartialShipmentAllowedListener")
class PartialShipmentAllowedListener {

    private final PickLists pickLists;

    PartialShipmentAllowedListener(PickLists pickLists) {
        this.pickLists = pickLists;
    }

    @ApplicationModuleListener
    public void on(OrderPartialShipmentAllowed event) {
        pickLists.liftHoldForRemovedCartons(event.orderId(), event.decidedBy());
    }
}
