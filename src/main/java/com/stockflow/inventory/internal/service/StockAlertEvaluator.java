package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.inventory.api.ReplenishmentEligibility;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.domain.StockAlert.Key;
import com.stockflow.inventory.internal.domain.StockAlert.Kind;
import com.stockflow.inventory.internal.domain.StockThresholds;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository;
import com.stockflow.inventory.internal.repository.InventoryPolicyRepository.ThresholdRow;
import com.stockflow.inventory.internal.repository.StockAlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.Set;
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
 *
 * <p>Only a SKU that may be bought again is watched ({@link ReplenishmentEligibility}: an ACTIVE variant of an
 * APPROVED or PUBLISHED product, the rule of a new purchase order line, kltn-docs 01 BR-04, 02 BR-01). A reorder alert
 * on a draft, a product under review, a suspended or discontinued one, or a blocked or obsolete variant would ask for
 * a purchase order nobody may raise; its open alerts close {@code NOT_PURCHASABLE}. A SKU that becomes purchasable
 * again is picked up by the next evaluation that reaches it (a stock change, a threshold change, the nightly
 * reconcile). Expiry alerts are not this class's business and do not ask.</p>
 */
@Component
class StockAlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(StockAlertEvaluator.class);

    private final InventoryPolicyRepository policies;
    private final StockAlertRepository alerts;
    private final AlertEpisodes episodes;
    private final ObjectProvider<ReplenishmentEligibility> eligibility;
    private final Clock clock;

    /**
     * @param eligibility implemented by the product module. Absent only when inventory is booted without it (a module
     *                    slice test): then nothing is known about products and every SKU is watched, as before.
     */
    StockAlertEvaluator(InventoryPolicyRepository policies, StockAlertRepository alerts, AlertEpisodes episodes,
                        ObjectProvider<ReplenishmentEligibility> eligibility, Clock clock) {
        this.policies = policies;
        this.alerts = alerts;
        this.episodes = episodes;
        this.eligibility = eligibility;
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
        ReplenishmentEligibility owner = eligibility.getIfAvailable();
        Set<String> replenishable = owner == null ? ordered : owner.replenishable(ordered);

        List<StockAlert> announced = new ArrayList<>();
        for (String sku : ordered) {
            if (!replenishable.contains(sku)) {
                for (Kind kind : List.of(Kind.REORDER, Kind.SAFETY)) {
                    StockAlert existing = open.get(Key.stockLevel(kind, sku));
                    if (existing != null) {
                        episodes.settle(existing, StockAlert.Resolution.NOT_PURCHASABLE, now);
                    }
                }
                continue;
            }
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
