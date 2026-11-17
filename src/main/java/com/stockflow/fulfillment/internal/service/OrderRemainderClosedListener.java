package com.stockflow.fulfillment.internal.service;

import com.stockflow.contracts.OrderRemainderClosed;
import com.stockflow.fulfillment.internal.domain.PickStatus;
import com.stockflow.fulfillment.internal.entity.PackJpaEntity;
import com.stockflow.fulfillment.internal.repository.PackJpaRepository;
import com.stockflow.fulfillment.internal.repository.PickJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/**
 * Sales closed the rest of a partly fulfilled order (kltn-docs 17 §4.8 step 33; owner decision WF4-CLOSE-REST,
 * 2026-11-13): the warehouse stops the closed part, the way a cancellation stops the whole order (17 §4.8 step 32,
 * {@link OrderCancellationListener}), and keeps what is labelled.
 *
 * <ul>
 *   <li>A task with a labelled carton - in a shipment waiting at dispatch, or labelled and not completed into one - goes
 *       on: its open pick lines are cancelled (their holds were released by the closure), its cartons not labelled are
 *       REMOVED into the put-back queue, back onto a bin, and a task back at PICKING for a top-up is PICKED again. Its
 *       check is COMPLETED when nothing labelled is left to ship, so the labelled cartons are handed over as before.</li>
 *   <li>A task with nothing labelled is cancelled whole, as a cancelled order's is: picked or staged cartons wait in the
 *       put-back queue, its open lines and its check are cancelled.</li>
 * </ul>
 *
 * <p>The order kept exactly the labelled units (it asked {@code LabelledCartons} under its lock, and every label takes
 * that lock and is refused once the rest is closed), so what stays here is what the order still wants. Each pick is
 * locked before anything changes, the lock a pick confirmation and the check station take first. Idempotent: the event
 * is delivered at least once, and a closed rest leaves nothing to close again.</p>
 */
@Component("fulfillmentOrderRemainderClosedListener")
class OrderRemainderClosedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderRemainderClosedListener.class);

    private final PickJpaRepository picks;
    private final PackJpaRepository packs;
    private final PickListService pickLists;
    private final CartonCheckService cartonChecks;
    private final Clock clock;

    OrderRemainderClosedListener(PickJpaRepository picks, PackJpaRepository packs, PickListService pickLists,
                                 CartonCheckService cartonChecks, Clock clock) {
        this.picks = picks;
        this.packs = packs;
        this.pickLists = pickLists;
        this.cartonChecks = cartonChecks;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void on(OrderRemainderClosed event) {
        picks.lockAllByOrderId(event.orderId()).forEach(pick -> {
            if (pick.getStatus() == PickStatus.CANCELLED) {
                return;
            }
            Optional<PackJpaEntity> pack = packs.findByPickId(pick.getId());
            if (pack.isPresent() && cartonChecks.closeRestForOrder(pick, pack.get(), event.closedBy())) {
                pickLists.cancelOpenLines(pick);
                if (pick.pickedOnClosedRest(clock.instant())) {
                    picks.save(pick);
                }
                log.info("Rest of order {} closed: task {} keeps its labelled cartons", event.orderNumber(),
                        pick.getId());
                return;
            }
            pick.cancel();
            picks.save(pick);
            pickLists.cancelOpenLines(pick);
            pack.ifPresent(found -> {
                cartonChecks.cancelForClosedRest(pick, found, event.closedBy());
                found.cancel();
                packs.save(found);
            });
            log.info("Rest of order {} closed: task {} cancelled{}", event.orderNumber(), pick.getId(),
                    pick.isNeedsPutBack() ? "; picked goods to be put back" : "");
        });
    }
}
