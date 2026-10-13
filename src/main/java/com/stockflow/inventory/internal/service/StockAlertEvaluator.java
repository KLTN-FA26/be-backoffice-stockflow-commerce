package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.domain.StockAlert.Key;
import com.stockflow.inventory.internal.domain.StockAlert.Kind;
import com.stockflow.inventory.internal.domain.StockThresholds;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository.ThresholdRow;
import com.stockflow.inventory.internal.repository.StockAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Compares SKUs with their thresholds and keeps their REORDER and SAFETY alerts in step (SCRUM-165).
 *
 * <p>It re-reads the current policy and stock, whatever event or job asked: an old trigger must not restore stale
 * settings ({@code SkuInventoryControlChanged}). The comparison itself is {@link StockThresholds}, the quantity is
 * the usable on-hand of {@link InventoryPolicyRepository}, so this is the same definition of "low" as
 * {@code InventoryControlService.evaluate} and {@code InventoryService.lowStockBatch}.</p>
 *
 * <p>Per SKU and kind: threshold crossed and no open alert, open one; crossed and one open, refresh its numbers;
 * not crossed and one open, close it {@code RECOVERED}; threshold removed, close it {@code UNCONFIGURED} (no message:
 * somebody deliberately stopped watching). What is genuinely new is announced once per call, grouped by kind.</p>
 */
@Component
class StockAlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(StockAlertEvaluator.class);

    private final InventoryPolicyRepository policies;
    private final StockAlertRepository alerts;
    private final AlertEpisodes episodes;
    private final Clock clock;

    StockAlertEvaluator(InventoryPolicyRepository policies, StockAlertRepository alerts, AlertEpisodes episodes,
                        Clock clock) {
        this.policies = policies;
        this.alerts = alerts;
        this.episodes = episodes;
        this.clock = clock;
    }

    /**
     * Evaluates these SKUs in one transaction.
     *
     * @return how many alerts were announced
     */
    @Transactional
    public int evaluate(Collection<String> skus) {
        if (skus == null || skus.isEmpty()) {
            return 0;
        }
        // Sorted, and under a lock of its own: two evaluations of one SKU (the drain and a policy-change
        // listener) queue instead of racing, and sorted keys cannot deadlock.
        TreeSet<String> ordered = new TreeSet<>(skus);
        ordered.forEach(alerts::lockEvaluation);
        Instant now = clock.instant();
        LocalDate today = BusinessCalendar.date(now);

        Map<String, ThresholdRow> rows = new HashMap<>();
        policies.thresholds(ordered, today).forEach(row -> rows.put(row.sku(), row));
        Map<Key, StockAlert> open = new HashMap<>();
        alerts.findOpenStockLevel(ordered).forEach(a -> open.put(a.key(), a));

        List<StockAlert> announced = new ArrayList<>();
        for (String sku : ordered) {
            ThresholdRow row = rows.get(sku);
            for (Kind kind : List.of(Kind.REORDER, Kind.SAFETY)) {
                Key key = Key.stockLevel(kind, sku);
                Integer threshold = row == null ? null : kind == Kind.REORDER ? row.reorderPoint() : row.safetyStock();
                long usable = row == null ? 0 : row.usableOnHand();
                Boolean crossed = kind == Kind.REORDER
                        ? StockThresholds.reorderRequired(usable, threshold)
                        : StockThresholds.belowSafetyStock(usable, threshold);
                StockAlert existing = open.get(key);
                if (Boolean.TRUE.equals(crossed)) {
                    episodes.raise(existing, key, Math.toIntExact(usable), threshold, false, now).ifPresent(announced::add);
                } else if (existing != null) {
                    episodes.settle(existing, threshold == null
                            ? StockAlert.Resolution.UNCONFIGURED : StockAlert.Resolution.RECOVERED, now);
                }
            }
        }
        episodes.announce(announced, now);
        if (!announced.isEmpty()) {
            log.info("Stock alerts: {} new low-stock alert(s) from {} SKU(s) evaluated", announced.size(), ordered.size());
        }
        return announced.size();
    }
}
