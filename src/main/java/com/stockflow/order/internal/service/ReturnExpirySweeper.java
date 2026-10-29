package com.stockflow.order.internal.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Expires returns whose approved goods did not arrive by their deadline (03 §12: AWAITING_RETURN → EXPIRED;
 * {@code stockflow.order.returns.deadline-days}, 14). Hourly, so a return expires within the hour its deadline passed and
 * the order it held back may complete that night (BR-ORD-005). One instance at a time; each return in its own
 * transaction, so one that fails does not hold the others back, and is tried again at the next run.
 */
@Component
class ReturnExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReturnExpirySweeper.class);
    static final int BATCH_SIZE = 100;
    /** At most 20 batches (2 000 returns) a run, well inside the lock. */
    static final int MAX_BATCHES = 20;

    private final ReturnRequestService returns;

    ReturnExpirySweeper(ReturnRequestService returns) {
        this.returns = returns;
    }

    @Scheduled(cron = "${stockflow.order.returns.expiry-cron:0 10 * * * *}", zone = "Asia/Ho_Chi_Minh")
    @SchedulerLock(name = "order.return-expiry", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    public void expireOverdueReturns() {
        int expired = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<UUID> due = returns.expiryCandidates(BATCH_SIZE);
            int moved = 0;
            for (UUID returnId : due) {
                moved += expire(returnId);
            }
            expired += moved;
            // Nothing moved: what is left is failing and would come back in the same order - next run.
            if (due.size() < BATCH_SIZE || moved == 0) {
                break;
            }
        }
        if (expired > 0) {
            log.info("Return expiry: {} return(s) expired", expired);
        }
    }

    private int expire(UUID returnId) {
        try {
            return returns.expire(returnId) ? 1 : 0;
        } catch (RuntimeException failed) {
            log.warn("Return {} could not be expired: {}", returnId, failed.toString());
            return 0;
        }
    }
}
