package com.stockflow.inventory.internal.service;

import com.stockflow.common.id.Identifiers;
import com.stockflow.contracts.InventoryAlertsRaised;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.domain.StockAlert.Key;
import com.stockflow.inventory.internal.repository.StockAlertRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The life of an alert episode, shared by the stock-level evaluator and the nightly expiry sweep: open a condition
 * (once), keep its numbers fresh, close it, and announce what is genuinely new.
 *
 * <p><b>Two layers against spam.</b> This class is the first: an alert that is already open is only refreshed, never
 * announced again, and one that comes back within the <i>re-touch window</i> after its previous episode closed is
 * opened (the list shows it) but not announced, so stock that jitters around its reorder point produces one message,
 * not one per crossing. The inbox's per-day {@code dedupe_key} is the second layer.</p>
 *
 * <p>Methods run in the caller's transaction (they only ever join one): the unique index decides a race, and an
 * announcement is published in the same transaction, so it exists exactly when the alert row does.</p>
 */
@Component
class AlertEpisodes {

    private final StockAlertRepository alerts;
    private final ApplicationEventPublisher events;
    private final Duration reopenCooldown;

    AlertEpisodes(StockAlertRepository alerts, ApplicationEventPublisher events,
                  @Value("${stockflow.inventory.alert-reopen-cooldown:PT6H}") Duration reopenCooldown) {
        if (reopenCooldown == null || reopenCooldown.isNegative()) {
            throw new IllegalArgumentException("stockflow.inventory.alert-reopen-cooldown must not be negative");
        }
        this.alerts = alerts;
        this.events = events;
        this.reopenCooldown = reopenCooldown;
    }

    /**
     * The condition holds now.
     *
     * @param existing the open episode of {@code key}, or null when there is none
     * @return the new alert when an episode was opened <b>and</b> is news (not inside the re-touch window); empty
     *         when the condition was already open, was opened by someone else first, or is a quiet reopening
     */
    Optional<StockAlert> raise(StockAlert existing, Key key, int observedQty, Integer thresholdQty,
                               boolean reservationImpaired, Instant now) {
        if (existing != null) {
            boolean changed = existing.observedQty() != observedQty
                    || !Objects.equals(existing.thresholdQty(), thresholdQty)
                    || existing.reservationImpaired() != reservationImpaired;
            if (changed) {
                existing.observe(observedQty, thresholdQty, reservationImpaired, now);
                alerts.update(existing);
            }
            return Optional.empty();
        }
        boolean quiet = alerts.lastResolvedAt(key).map(end -> end.isAfter(now.minus(reopenCooldown))).orElse(false);
        StockAlert fresh = StockAlert.open(Identifiers.newId(), key, observedQty, thresholdQty, reservationImpaired, now);
        if (!alerts.insertIfAbsent(fresh)) {
            return Optional.empty();
        }
        return quiet ? Optional.empty() : Optional.of(fresh);
    }

    /** The condition ended. */
    void settle(StockAlert open, StockAlert.Resolution resolution, Instant now) {
        if (open.resolve(resolution, now)) {
            alerts.update(open);
        }
    }

    /** One {@link InventoryAlertsRaised} per kind and warehouse of the given new alerts; nothing for an empty list. */
    void announce(List<StockAlert> opened, Instant now) {
        Map<List<Object>, List<InventoryAlertsRaised.Item>> groups = new LinkedHashMap<>();
        for (StockAlert a : opened) {
            groups.computeIfAbsent(java.util.Arrays.asList(a.kind(), a.warehouseId()), k -> new ArrayList<>())
                    .add(new InventoryAlertsRaised.Item(a.id(), a.sku(), a.lotNumber(), a.expiryDate(), a.observedQty(),
                            a.thresholdQty(), a.reservationImpaired()));
        }
        groups.forEach((group, items) -> events.publishEvent(new InventoryAlertsRaised(UUID.randomUUID(),
                ((StockAlert.Kind) group.get(0)).name(), (UUID) group.get(1), items, now)));
    }
}
