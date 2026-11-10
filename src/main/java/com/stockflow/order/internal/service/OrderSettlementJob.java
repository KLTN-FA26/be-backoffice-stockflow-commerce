package com.stockflow.order.internal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Nightly (Saigon) completion and closing of orders (G3-2, see {@link OrderSettlementService}). One instance at a time;
 * bounded batches, each order in its own transaction. A candidate the aggregate refuses (still owing, a return open)
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
        int completed = drain(true);
        int closed = drain(false);
        if (completed + closed > 0) {
            log.info("Order settlement: {} completed, {} closed", completed, closed);
        }
    }

    /**
     * Batch after batch until nothing is left (review of G3-2: one batch a night fell behind), bounded per run. What the
     * aggregate refuses (still owing on its installments, a return open) or what fails stays a candidate, in the same
     * place: the next batch skips as many as were left behind, so they do not hold back the orders after them (4B-10 -
     * the query can no longer tell an owing order, which is payment's to say). They are tried again the next night. Each
     * order is its own transaction, so a run cut short by the lock's limit leaves every order either done or
     * untouched.
     */
    private int drain(boolean complete) {
        int done = 0;
        int leftBehind = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<UUID> ids = complete ? settlements.completionCandidates(leftBehind, BATCH_SIZE)
                    : settlements.closingCandidates(leftBehind, BATCH_SIZE);
            int moved = 0;
            for (UUID orderId : ids) {
                moved += run(orderId, complete);
            }
            done += moved;
            leftBehind += ids.size() - moved;
            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }
        return done;
    }

    private int run(UUID orderId, boolean complete) {
        try {
            return (complete ? settlements.complete(orderId) : settlements.close(orderId)) ? 1 : 0;
        } catch (RuntimeException failed) {
            // One order must not stop the rest; it is tried again tomorrow.
            log.warn("Order {} could not be {}: {}", orderId, complete ? "completed" : "closed", failed.toString());
            return 0;
        }
    }
}
