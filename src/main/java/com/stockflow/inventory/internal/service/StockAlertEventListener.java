package com.stockflow.inventory.internal.service;

import com.stockflow.contracts.SkuInventoryControlChanged;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A SKU's thresholds were saved: evaluate it right away, so setting a reorder point above the current stock shows the
 * alert in seconds instead of at the next drain (SCRUM-70/71 gate: "evaluated immediately after commit").
 *
 * <p>The listener re-reads policy and stock inside the evaluator and ignores {@code revision}: an old event must not
 * restore old settings. A failure leaves the event in the publication registry to be retried.</p>
 */
@Component
class StockAlertEventListener {

    private final StockAlertEvaluator evaluator;

    StockAlertEventListener(StockAlertEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    @ApplicationModuleListener
    public void on(SkuInventoryControlChanged event) {
        evaluator.evaluate(List.of(event.sku()));
    }
}
