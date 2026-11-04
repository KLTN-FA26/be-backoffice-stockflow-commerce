package com.stockflow.inventory.internal.service;

import com.stockflow.common.domain.BusinessCalendar;
import com.stockflow.common.id.Identifiers;
import com.stockflow.inventory.internal.domain.ExpiryPolicy;
import com.stockflow.inventory.internal.domain.LocationId;
import com.stockflow.inventory.internal.domain.StockAlert;
import com.stockflow.inventory.internal.domain.StockAlert.Key;
import com.stockflow.inventory.internal.domain.StockAlert.Kind;
import com.stockflow.inventory.internal.domain.StockItem;
import com.stockflow.inventory.internal.domain.StockItemId;
import com.stockflow.inventory.internal.domain.StockItemRepository;
import com.stockflow.inventory.internal.domain.StockMovement;
import com.stockflow.inventory.internal.domain.StockMovementLog;
import com.stockflow.inventory.internal.domain.StockStatus;
import com.stockflow.inventory.internal.repository.StockAlertRepository;
import com.stockflow.inventory.internal.repository.StockExpiryRepository;
import com.stockflow.inventory.internal.repository.StockExpiryRepository.LotRow;
import com.stockflow.warehouse.api.StorageLocationView;
import com.stockflow.warehouse.api.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The nightly expiry sweep (flow F-EXP, BR-STK-004, SCRUM-166; the work of {@code ExpirySweepJob}).
 *
 * <p>Two steps, each in transactions of their own so a failure in one does not undo the other:</p>
 * <ol>
 *   <li><b>Block.</b> Every AVAILABLE stock row past its expiry date moves to EXPIRED and leaves a
 *       {@code STATUS_CHANGE} line in the ledger ({@code EXPIRY_SWEEP}, the stock row as document). ATP already
 *       ignores such a row when it reads; this makes the status and the history true. A row that a customer's
 *       reservation still holds is <b>not</b> touched ({@link StockItem#block} refuses, rightly: an order would
 *       believe it still has the goods); its alert says {@code reservationImpaired}. Each row is its own
 *       transaction, locked by id like every stock write, so one stuck row does not hold up the rest.</li>
 *   <li><b>Alert.</b> Recomputes which lots of which warehouse are near expiry (today up to today+N days, N =
 *       {@code stockflow.inventory.expiry-warning-days}) or expired, and brings the EXPIRING and EXPIRED alerts in step:
 *       open what is new, refresh what is open, close what is gone ({@code STOCK_GONE}, or {@code EXPIRED_OUT} for a
 *       near-expiry alert whose lot has now expired). What is new is announced once per kind and warehouse.</li>
 * </ol>
 *
 * <p><b>Idempotent:</b> a second run the same day finds nothing to block, the same alerts open, and announces nothing.</p>
 */
@Component
class ExpirySweeper {

    static final int BLOCK_BATCH = 200;

    private static final Logger log = LoggerFactory.getLogger(ExpirySweeper.class);

    /** What one run did. */
    record Result(int blocked, int impaired, int announced, int resolved) {
    }

    private final StockItemRepository stockItems;
    private final StockMovementLog ledger;
    private final StockExpiryRepository expiry;
    private final StockAlertRepository alerts;
    private final AlertEpisodes episodes;
    private final WarehouseService warehouses;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int warningDays;

    ExpirySweeper(StockItemRepository stockItems, StockMovementLog ledger, StockExpiryRepository expiry,
                  StockAlertRepository alerts, AlertEpisodes episodes, WarehouseService warehouses,
                  TransactionTemplate tx, Clock clock,
                  @Value("${stockflow.inventory.expiry-warning-days:30}") int warningDays) {
        if (warningDays < 1) {
            throw new IllegalArgumentException("stockflow.inventory.expiry-warning-days must be at least 1");
        }
        this.stockItems = stockItems;
        this.ledger = ledger;
        this.expiry = expiry;
        this.alerts = alerts;
        this.episodes = episodes;
        this.warehouses = warehouses;
        this.tx = tx;
        this.clock = clock;
        this.warningDays = warningDays;
    }

    Result sweep() {
        Instant now = clock.instant();
        LocalDate today = BusinessCalendar.date(now);
        int blocked = blockExpired(today, now);
        Result refreshed = tx.execute(status -> refreshAlerts(today, now));
        Result result = new Result(blocked, refreshed.impaired(), refreshed.announced(), refreshed.resolved());
        log.info("Expiry sweep for {}: {} stock row(s) moved to EXPIRED, {} still held by reservations, {} alert(s) announced, {} closed",
                today, result.blocked(), result.impaired(), result.announced(), result.resolved());
        return result;
    }

    // ------------------------------------------------------------------ step 1: block

    private int blockExpired(LocalDate today, Instant now) {
        int blocked = 0;
        UUID after = new UUID(0, 0);
        while (true) {
            final UUID cursor = after;
            List<UUID> ids = tx.execute(status -> expiry.expiredAvailableIds(today, cursor, BLOCK_BATCH));
            if (ids == null || ids.isEmpty()) {
                return blocked;
            }
            for (UUID id : ids) {
                try {
                    if (Boolean.TRUE.equals(tx.execute(status -> blockOne(id, today, now)))) {
                        blocked++;
                    }
                } catch (RuntimeException failure) {
                    // One row that cannot be locked or saved must not stop the night; it is found again tomorrow.
                    log.warn("Expiry sweep could not block stock row {} ({})", id, failure.getClass().getSimpleName());
                }
            }
            after = ids.get(ids.size() - 1);
            if (ids.size() < BLOCK_BATCH) {
                return blocked;
            }
        }
    }

    private boolean blockOne(UUID id, LocalDate today, Instant now) {
        Optional<StockItem> found = stockItems.findByIdForUpdate(new StockItemId(id));
        if (found.isEmpty()) {
            return false;
        }
        StockItem item = found.get();
        // Re-checked under the lock: the row may have been moved, scrapped or blocked since the scan.
        if (item.status() != StockStatus.AVAILABLE || !ExpiryPolicy.isExpired(item.expiryDate(), today)
                || item.onHand().isZero() || !ExpiryPolicy.canBlock(item.reserved().value())) {
            return false;
        }
        item.block(StockStatus.EXPIRED);
        stockItems.save(item);
        LocationId where = item.location();
        ledger.append(new StockMovement(Identifiers.newId(), StockMovement.MovementType.STATUS_CHANGE, item.sku(),
                item.lotNumber(), where, where, item.onHand().value(), StockStatus.AVAILABLE, StockStatus.EXPIRED,
                StockMovement.ReferenceType.EXPIRY_SWEEP, item.id().value(), "EXPIRY_SWEEP:" + today, null, now)
                .ofLot(item.lotId()));
        return true;
    }

    // ------------------------------------------------------------------ step 2: alerts

    private Result refreshAlerts(LocalDate today, Instant now) {
        List<LotRow> lots = expiry.lots(today, today.plusDays(warningDays));
        Map<Key, StockAlert> open = new HashMap<>();
        alerts.findOpenOfKinds(Kind.EXPIRING, Kind.EXPIRED).forEach(a -> open.put(a.key(), a));

        Map<String, Optional<UUID>> warehouseOfPrefix = new HashMap<>();
        Map<Key, LotRow> wanted = new LinkedHashMap<>();
        for (LotRow lot : lots) {
            Optional<UUID> warehouse = warehouseOfPrefix.computeIfAbsent(lot.warehousePrefix(),
                    prefix -> warehouses.findLocation(lot.locationSample()).map(StorageLocationView::warehouseId));
            if (warehouse.isEmpty()) {
                log.warn("Expiry sweep: location {} has no warehouse; its lot is skipped", lot.locationSample());
                continue;
            }
            Kind kind = ExpiryPolicy.alertKind(lot.expiryDate(), today, warningDays).orElseThrow();
            wanted.put(Key.expiry(kind, lot.sku(), warehouse.get(), lot.lotNumber(), lot.expiryDate()), lot);
        }

        List<StockAlert> announced = new ArrayList<>();
        int impaired = 0;
        for (Map.Entry<Key, LotRow> entry : wanted.entrySet()) {
            Key key = entry.getKey();
            LotRow lot = entry.getValue();
            boolean held = key.kind() == Kind.EXPIRED && lot.impaired();
            if (held) {
                impaired++;
            }
            episodes.raise(open.get(key), key, Math.toIntExact(lot.onHand()), null, held, now).ifPresent(announced::add);
        }

        int resolved = 0;
        for (Map.Entry<Key, StockAlert> entry : open.entrySet()) {
            if (wanted.containsKey(entry.getKey())) {
                continue;
            }
            StockAlert gone = entry.getValue();
            boolean expiredMeanwhile = gone.kind() == Kind.EXPIRING && ExpiryPolicy.isExpired(gone.expiryDate(), today);
            episodes.settle(gone, expiredMeanwhile ? StockAlert.Resolution.EXPIRED_OUT : StockAlert.Resolution.STOCK_GONE, now);
            resolved++;
        }
        episodes.announce(announced, now);
        return new Result(0, impaired, announced.size(), resolved);
    }
}
