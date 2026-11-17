package com.stockflow.fulfillment.internal.service;

import com.stockflow.contracts.OrderCancelled;
import com.stockflow.contracts.RedeliveryWithdrawn;
import com.stockflow.fulfillment.internal.entity.PickJpaEntity;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * kltn-docs 17 §4.5, BR-03: cancelling an order before it ships stops the warehouse work — the pick
 * and the pack of the order are cancelled, and a pick already started is flagged for its goods to be
 * put back (SCRUM-460). Its open pick lines are cancelled with it; picked lines stay PICKED and staged lines STAGED
 * (their cartons in the staging area, the allocation released by the cancellation - team plan 4C-5), and both show up
 * in the put-back queue (SCRUM-275, kltn-docs 07 §4.5 step 13). The check station's cartons not shipped are REMOVED and
 * its session CANCELLED (kltn-docs 08 BR-04, team plan 4C-6), and a shipment still waiting for dispatch is cancelled
 * (G4-5) - an order with a labelled carton is not cancelled at
 * all (08 BR-04, 17 BR-12). An order that never reached fulfillment has nothing here.
 *
 * <p>The pick is locked before anything changes, the same lock a pick confirmation takes first, so a
 * confirmation and this listener never interleave on one task.</p>
 *
 * <p>The same stop, narrowed to one task, when Sales withdraw a redelivery already released to the warehouse
 * ({@link RedeliveryWithdrawn}, WF-05 review F3): only the task picking that delivery failure case's goods again is
 * cancelled; the order's other tasks go on.</p>
 *
 * <p>Idempotent: the event is delivered at least once, and a cancelled pick stays cancelled.</p>
 */
@Component("fulfillmentOrderCancellationListener")
class OrderCancellationListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCancellationListener.class);

    private final PickJpaRepository picks;
    private final PackJpaRepository packs;
    private final PickListService pickLists;
    private final CartonCheckService cartonChecks;

    OrderCancellationListener(PickJpaRepository picks, PackJpaRepository packs, PickListService pickLists,
                              CartonCheckService cartonChecks) {
        this.picks = picks;
        this.packs = packs;
        this.pickLists = pickLists;
        this.cartonChecks = cartonChecks;
    }

    @ApplicationModuleListener
    public void on(OrderCancelled event) {
        // 4B-8: an order releasing by delivery batch has a task per batch; a task already done is left as it is.
        picks.lockAllByOrderId(event.orderId()).forEach(pick -> cancel(pick, event.orderNumber()));
    }

    @ApplicationModuleListener
    public void on(RedeliveryWithdrawn event) {
        // Every task of the order is locked, in the order the order's cancellation locks them, then only the one
        // picking the case's goods again is cancelled.
        picks.lockAllByOrderId(event.orderId()).stream()
                .filter(pick -> event.caseId().equals(pick.getRedeliveryCaseId()))
                .forEach(pick -> cancel(pick, event.orderNumber()));
    }

    private void cancel(PickJpaEntity pick, String orderNumber) {
        if (!pick.cancel()) {
            return;
        }
        picks.save(pick);
        pickLists.cancelOpenLines(pick);
        packs.findByPickId(pick.getId()).ifPresent(pack -> {
            // 08 BR-04, BR-05: a shipment not handed over is cancelled, the cartons not shipped go to the put-back
            // queue, the session is cancelled.
            cartonChecks.cancelForOrder(pick, pack);
            pack.cancel();
            packs.save(pack);
        });
        log.info("Fulfillment task {} of order {} cancelled{}", pick.getId(), orderNumber,
                pick.isNeedsPutBack() ? "; picked goods to be put back" : "");
    }
}
