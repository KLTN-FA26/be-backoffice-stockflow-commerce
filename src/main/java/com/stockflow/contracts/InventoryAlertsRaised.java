package com.stockflow.contracts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * New stock alerts of one kind, opened together by one evaluation or one night's expiry sweep.
 *
 * <p>Published only when an episode is <b>opened</b>, never when an open alert's numbers move, and not
 * for an episode that reopens inside the re-touch window (a stock level that jitters around its threshold
 * must not tell people again and again). That is the first of two layers against spam; the second is the
 * inbox's {@code dedupe_key}.</p>
 *
 * <p><b>Facts, not text</b> (hotfix #99, {@code verify.py} check 16): the publication is stored for good, so the
 * record carries codes, ids, dates and numbers only. Whoever listens words its own message from them.</p>
 *
 * @param batchId     identifies this batch for logging and idempotence
 * @param kind        {@code REORDER}, {@code SAFETY}, {@code EXPIRING} or {@code EXPIRED}
 * @param warehouseId the warehouse of an expiry alert; {@code null} for REORDER and SAFETY, whose thresholds
 *                    are per SKU for the whole system
 * @param items       the alerts opened, at least one
 */
public record InventoryAlertsRaised(UUID batchId, String kind, UUID warehouseId, List<Item> items,
                                    Instant occurredAt) {

    public InventoryAlertsRaised {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * @param thresholdQty        the reorder point or safety stock the stock fell to; {@code null} for expiry alerts
     * @param reservationImpaired an expired lot that still holds a customer's reservation
     */
    public record Item(UUID alertId, String sku, String lotNumber, LocalDate expiryDate, int observedQty,
                       Integer thresholdQty, boolean reservationImpaired) {
    }
}
