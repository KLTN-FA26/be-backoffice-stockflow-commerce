package com.stockflow.fulfillment.internal.service;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * kltn-docs 17 §4.5, BR-03: cancelling an order before it ships stops the warehouse work — the pick
 * and the pack of the order are cancelled, and a pick already started is flagged for its goods to be
 * put back (SCRUM-460). Its open pick lines are cancelled with it; picked lines stay PICKED and show up
 * in the put-back queue (SCRUM-275). Parcels packed for it and not shipped are voided, with their labels, and a
 * shipment still waiting for dispatch is cancelled (kltn-docs 08 BR-05, G4-5) - an order with a completed package is
 * not cancelled at all (decision 2026-10-17). An order that never reached fulfillment has nothing here.
 *
 * <p>The pick is locked before anything changes, the same lock a pick confirmation takes first, so a
 * confirmation and this listener never interleave on one task.</p>
 *
 * <p>Idempotent: the event is delivered at least once, and a cancelled pick stays cancelled.</p>
 */
@Component("fulfillmentOrderCancellationListener")
class OrderCancellationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationListener.class);

    private final PickJpaRepository picks;
    private final PackJpaRepository packs;
    private final PickListService pickLists;
    private final PackingService packing;

    OrderCancellationListener(PickJpaRepository picks, PackJpaRepository packs, PickListService pickLists,
                              PackingService packing) {
        this.picks = picks;
        this.packs = packs;
        this.pickLists = pickLists;
        this.packing = packing;
    }

    @ApplicationModuleListener
    public void on(OrderCancelled event) {
        picks.lockByOrderId(event.orderId()).ifPresent(pick -> {
            if (!pick.cancel()) {
                return;
            }
            picks.save(pick);
            pickLists.cancelOpenLines(pick);
            packs.findByPickId(pick.getId()).ifPresent(pack -> {
                // 08 BR-05: parcels not shipped are voided with their labels, a shipment not handed over cancelled.
                packing.cancelForOrder(pick, pack);
                pack.cancel();
                packs.save(pack);
            });
            log.info("Fulfillment of order {} cancelled{}", event.orderNumber(),
                    pick.isNeedsPutBack() ? "; picked goods to be put back" : "");
        });
    }
}
