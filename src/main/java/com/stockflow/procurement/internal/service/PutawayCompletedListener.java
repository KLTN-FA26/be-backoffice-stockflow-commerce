package com.stockflow.procurement.internal.service;

import com.stockflow.contracts.PutawayCompleted;
import com.stockflow.procurement.internal.domain.GoodsReceipt;
import com.stockflow.procurement.internal.domain.GoodsReceiptRepository;
import com.stockflow.warehouse.api.PutawayTasks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/**
 * SCRUM-92: closes a goods receipt once every line's goods are put away (docs 03, {@code IN_PUTAWAY -> CLOSED}).
 *
 * <p>Asynchronous and in its own transaction: closing the receipt must not hold up, or roll back, the putaway
 * that triggered it. It runs without a user and goes through the repository port, never through the service's
 * warehouse-scope check, which is a rule about what a person may see and has no person to apply to here.</p>
 *
 * <p>Idempotent: the event is delivered at least once, and two tasks finishing together deliver two events. The
 * decision is taken from the quantities put away at the time the listener runs ({@code closeIfPutAway}), so the
 * later of two events closes the receipt and the earlier one, or a redelivery, finds it already CLOSED and does
 * nothing. A receipt that is not in putaway, such as one still in QC, is left alone; its last QC decision tries
 * again.</p>
 */
@Component("procurementPutawayCompletedListener")
class PutawayCompletedListener {

    private static final Logger log = LoggerFactory.getLogger(PutawayCompletedListener.class);

    private final GoodsReceiptRepository receipts;
    private final PutawayTasks putawayTasks;
    private final Clock clock;

    PutawayCompletedListener(GoodsReceiptRepository receipts, PutawayTasks putawayTasks, Clock clock) {
        this.receipts = receipts;
        this.putawayTasks = putawayTasks;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void on(PutawayCompleted event) {
        Optional<GoodsReceipt> found = receipts.findById(event.goodsReceiptId());
        if (found.isEmpty()) {
            // Throwing would leave the publication to be retried for ever; a receipt that is gone cannot be closed.
            log.warn("Putaway task {} completed for unknown goods receipt {}", event.putawayTaskId(),
                    event.goodsReceiptId());
            return;
        }
        GoodsReceipt receipt = found.get();
        if (receipt.closeIfPutAway(putawayTasks.doneQuantityByReceiptLine(receipt.id()), clock.instant())) {
            receipts.save(receipt);
            log.info("Goods receipt {} closed: all its goods are put away", receipt.number());
        }
    }
}
