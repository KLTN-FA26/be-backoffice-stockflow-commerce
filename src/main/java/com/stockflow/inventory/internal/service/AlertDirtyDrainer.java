package com.stockflow.inventory.internal.service;

import com.stockflow.common.logging.LogSafe;
import com.stockflow.inventory.internal.repository.StockAlertDirtyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Takes the SKUs whose stock changed off the dirty queue and evaluates them (the work of {@code AlertDirtyDrainJob}).
 *
 * <p>The claim is its own short transaction, so the queue rows are gone before the evaluation starts and a slow
 * evaluation holds nothing. If a batch fails, its SKUs are evaluated one by one so a single bad SKU cannot starve the
 * rest, and the ones that still fail go back on the queue for the next drain (the retry; the nightly reconcile is the
 * net under it).</p>
 */
@Component
class AlertDirtyDrainer {

    static final int CLAIM_SIZE = 1000;
    static final int EVALUATION_BATCH = 200;

    private static final Logger log = LoggerFactory.getLogger(AlertDirtyDrainer.class);

    private final StockAlertDirtyRepository dirty;
    private final StockAlertEvaluator evaluator;
    private final TransactionTemplate tx;

    AlertDirtyDrainer(StockAlertDirtyRepository dirty, StockAlertEvaluator evaluator, TransactionTemplate tx) {
        this.dirty = dirty;
        this.evaluator = evaluator;
        this.tx = tx;
    }

    /**
     * Drains the queue once, in claims of {@value #CLAIM_SIZE} rows until it is empty.
     *
     * @return how many SKUs were evaluated
     */
    int drain() {
        int evaluated = 0;
        List<String> claimed;
        do {
            claimed = tx.execute(status -> dirty.claim(CLAIM_SIZE));
            if (claimed == null || claimed.isEmpty()) {
                break;
            }
            for (int from = 0; from < claimed.size(); from += EVALUATION_BATCH) {
                evaluated += evaluate(claimed.subList(from, Math.min(claimed.size(), from + EVALUATION_BATCH)));
            }
        } while (claimed.size() >= CLAIM_SIZE);
        return evaluated;
    }

    private int evaluate(List<String> batch) {
        try {
            evaluator.evaluate(batch);
            return batch.size();
        } catch (RuntimeException failure) {
            log.warn("Alert evaluation of {} SKU(s) failed ({}); retrying them one by one", batch.size(),
                    failure.getClass().getSimpleName());
        }
        int done = 0;
        List<String> failed = new ArrayList<>();
        for (String sku : batch) {
            try {
                evaluator.evaluate(List.of(sku));
                done++;
            } catch (RuntimeException failure) {
                failed.add(sku);
                log.warn("Alert evaluation of SKU {} failed ({}); queued again", LogSafe.text(sku),
                        failure.getClass().getSimpleName());
            }
        }
        if (!failed.isEmpty()) {
            tx.executeWithoutResult(status -> dirty.mark(failed));
        }
        return done;
    }
}
