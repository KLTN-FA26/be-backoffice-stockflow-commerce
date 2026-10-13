package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.inventory.internal.domain.StockThresholds;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository.ThresholdRow;
import com.stockflow.inventory.internal.repository.StockAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The nightly sweep over thresholds (the work of {@code AlertReconcileJob}): walks the SKUs that have a threshold in
 * keyset pages of {@value #PAGE_SIZE}, takes the ones that are low (the {@link StockThresholds} definition), adds the
 * SKUs that still have an open stock alert so those can close, and evaluates them in batches.
 */
@Component
class AlertReconciler {

    static final int PAGE_SIZE = 200;

    private static final Logger log = LoggerFactory.getLogger(AlertReconciler.class);

    private final InventoryPolicyRepository policies;
    private final StockAlertRepository alerts;
    private final StockAlertEvaluator evaluator;
    private final TransactionTemplate tx;
    private final Clock clock;

    AlertReconciler(InventoryPolicyRepository policies, StockAlertRepository alerts, StockAlertEvaluator evaluator,
                    TransactionTemplate tx, Clock clock) {
        this.policies = policies;
        this.alerts = alerts;
        this.evaluator = evaluator;
        this.tx = tx;
        this.clock = clock;
    }

    /** @return how many SKUs were evaluated */
    int reconcile() {
        LocalDate today = BusinessCalendar.date(clock.instant());
        Set<String> toEvaluate = new LinkedHashSet<>();
        String after = "";
        while (true) {
            final String cursor = after;
            List<ThresholdRow> page = tx.execute(status -> policies.thresholdPage(cursor, PAGE_SIZE, today));
            if (page == null || page.isEmpty()) {
                break;
            }
            page.stream().filter(r -> StockThresholds.isLow(r.usableOnHand(), r.reorderPoint(), r.safetyStock()))
                    .forEach(r -> toEvaluate.add(r.sku()));
            after = page.get(page.size() - 1).sku();
            if (page.size() < PAGE_SIZE) {
                break;
            }
        }
        toEvaluate.addAll(tx.execute(status -> alerts.skusWithOpenStockLevelAlerts()));
        List<String> all = new ArrayList<>(toEvaluate);
        int evaluated = 0;
        for (int from = 0; from < all.size(); from += PAGE_SIZE) {
            List<String> batch = all.subList(from, Math.min(all.size(), from + PAGE_SIZE));
            evaluator.evaluate(batch);
            evaluated += batch.size();
        }
        log.info("Alert reconcile evaluated {} SKU(s)", evaluated);
        return evaluated;
    }
}
