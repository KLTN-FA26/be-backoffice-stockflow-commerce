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
 * put back (SCRUM-460). An order that never reached fulfillment has nothing here.
 *
 * <p>Idempotent: the event is delivered at least once, and a cancelled pick stays cancelled.</p>
 */
@Component("fulfillmentOrderCancellationListener")
class OrderCancellationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationListener.class);

    private final PickJpaRepository picks;
    private final PackJpaRepository packs;

    OrderCancellationListener(PickJpaRepository picks, PackJpaRepository packs) {
        this.picks = picks;
        this.packs = packs;
    }

    @ApplicationModuleListener
    public void on(OrderCancelled event) {
        picks.findByOrderId(event.orderId()).ifPresent(pick -> {
            if (!pick.cancel()) {
                return;
            }
            picks.save(pick);
            packs.findByPickId(pick.getId()).ifPresent(pack -> {
                pack.cancel();
                packs.save(pack);
            });
            log.info("Fulfillment of order {} cancelled{}", event.orderNumber(),
                    pick.isNeedsPutBack() ? "; picked goods to be put back" : "");
        });
    }
}
