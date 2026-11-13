package com.stockflow.order.internal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Nightly (Saigon) completion and closing of orders (G3-2, see {@link OrderSettlementService}), and - WF-05 fix plan,
 * section 2 - the settling of orders whose failed delivery was cancelled: RETURNED, their refunds made, they move on
 * to REFUNDED / PARTIALLY_REFUNDED so that a later night closes them. One instance at a time; bounded batches, each
 * order in its own transaction. A candidate the aggregate refuses (still owing, a return open, a refund not made)
 * stays as it is and is looked at again the next night.
 */
@Component
class OrderSettlementJob {

    private static final Logger log = LoggerFactory.getLogger(OrderSettlementJob.class);
    static final int BATCH_SIZE = 200;
    /** At most 50 batches (10 000 orders) a run, well inside the lock's ten minutes. */
    static final int MAX_BATCHES = 50;

    private final OrderSettlementService settlements;

    OrderSettlementJob(OrderSettlementService settlements) {
        this.settlements = settlements;
    }

    @Scheduled(cron = "${stockflow.order.settlement-cron:0 30 0 * * *}", zone = "Asia/Ho_Chi_Minh")
    @SchedulerLock(name = "order.settle", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    public void settle() {
        int completed = drain(Step.COMPLETE);
        // Before the closing: an order whose failed delivery was cancelled and settled tonight is not old enough to
        // close tonight, but it must be REFUNDED / PARTIALLY_REFUNDED for a later night to find it.
        int settled = drain(Step.SETTLE_FAILED_DELIVERY);
        int closed = drain(Step.CLOSE);
        if (completed + settled + closed > 0) {
            log.info("Order settlement: {} completed, {} failed deliveries settled, {} closed", completed, settled,
                    closed);
        }
    }

    /** What a run does, in this order. */
    private enum Step { COMPLETE, SETTLE_FAILED_DELIVERY, CLOSE }

    /**
     * Batch after batch until nothing is left (review of G3-2: one batch a night fell behind), bounded per run. What the
     * aggregate refuses (still owing on its installments, a return open) or what fails stays a candidate, in the same
     * place: the next batch skips as many as were left behind, so they do not hold back the orders after them (4B-10 -
     * the query can no longer tell an owing order, which is payment's to say). They are tried again the next night. Each
     * order is its own transaction, so a run cut short by the lock's limit leaves every order either done or
     * untouched.
     */
    private int drain(Step step) {
        int done = 0;
        int leftBehind = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<UUID> ids = switch (step) {
                case COMPLETE -> settlements.completionCandidates(leftBehind, BATCH_SIZE);
                case SETTLE_FAILED_DELIVERY -> settlements.failedDeliveryCandidates(leftBehind, BATCH_SIZE);
                case CLOSE -> settlements.closingCandidates(leftBehind, BATCH_SIZE);
            };
            int moved = 0;
            for (UUID orderId : ids) {
                moved += run(orderId, step);
            }
            done += moved;
            leftBehind += ids.size() - moved;
            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }
        return done;
    }

    private int run(UUID orderId, Step step) {
        try {
            boolean moved = switch (step) {
                case COMPLETE -> settlements.complete(orderId);
                case SETTLE_FAILED_DELIVERY -> settlements.settleFailedDelivery(orderId);
                case CLOSE -> settlements.close(orderId);
            };
            return moved ? 1 : 0;
        } catch (RuntimeException failed) {
            // One order must not stop the rest; it is tried again tomorrow.
            log.warn("Order {} could not be settled ({}): {}", orderId, step, failed.toString());
            return 0;
        }
    }
}
