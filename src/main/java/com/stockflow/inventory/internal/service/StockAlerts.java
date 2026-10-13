package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.PageResponse;
import com.stockflow.inventory.internal.domain.StockAlert;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reading and acknowledging stock alerts (SCRUM-165/166): the back office's view of what the evaluator and the
 * nightly sweep found. Not on the published port: other modules learn of new alerts through
 * {@code InventoryAlertsRaised}.
 *
 * <p>Every method applies the caller's warehouse scope ({@code WarehouseScope}): a warehouse-limited user sees the
 * expiry alerts of their warehouses and none of the SKU-wide REORDER/SAFETY ones, which belong to nobody's warehouse.</p>
 */
public interface StockAlerts {

    /**
     * @param warehouseCode  the warehouse prefix ({@code HCM}); null for REORDER and SAFETY
     * @param daysToExpiry   {@code expiryDate - today} in business days; negative once expired; null without an expiry date
     * @param acknowledgedByName the display name of whoever acknowledged it
     */
    record AlertView(UUID id, StockAlert.Kind kind, String sku, UUID warehouseId, String warehouseCode,
                     String lotNumber, LocalDate expiryDate, Long daysToExpiry, int observedQty, Integer thresholdQty,
                     boolean reservationImpaired, StockAlert.Status status, Instant openedAt, Instant lastObservedAt,
                     UUID acknowledgedBy, String acknowledgedByName, Instant acknowledgedAt, String ackNote,
                     Instant resolvedAt, StockAlert.Resolution resolution, long version) {
    }

    /** A SKU with usable stock and no reorder point, so no alert can ever be raised for it. */
    record UnconfiguredView(String sku, long usableOnHand, Integer safetyStock) {
    }

    /**
     * Alerts, newest first by default. {@code sort} is {@code openedAt}, {@code kind} or {@code sku}, optionally
     * {@code ,desc}. Empty {@code kinds} or {@code statuses} mean any.
     *
     * @throws com.stockflow.common.error.BusinessException {@code OUT_OF_DATA_SCOPE} when {@code warehouseId} is
     *         outside the caller's warehouses; {@code UNSUPPORTED_PARAMETER} for a bad page, size or sort
     */
    PageResponse<AlertView> list(Set<StockAlert.Kind> kinds, Set<StockAlert.Status> statuses, String sku,
                                 UUID warehouseId, int page, int size, String sort);

    /** @throws com.stockflow.common.error.BusinessException {@code ALERT_NOT_FOUND}, {@code OUT_OF_DATA_SCOPE} */
    AlertView get(UUID id);

    /** Open (not resolved) alerts per kind inside the caller's scope; every kind is present, zero when none. */
    Map<StockAlert.Kind, Long> summary();

    /**
     * SKUs with usable stock and no reorder point.
     *
     * @throws com.stockflow.common.error.BusinessException {@code OUT_OF_DATA_SCOPE} for a warehouse-limited
     *         caller: thresholds are per SKU for the whole system, not per warehouse
     */
    PageResponse<UnconfiguredView> unconfigured(int page, int size);

    /**
     * Says the alert has been seen; the condition stays open.
     *
     * @param version the alert's version as the client read it
     * @throws com.stockflow.common.error.BusinessException {@code ALERT_NOT_FOUND}, {@code OUT_OF_DATA_SCOPE},
     *         {@code INVALID_ALERT_TRANSITION} (already acknowledged or resolved), {@code OPTIMISTIC_LOCK}
     */
    AlertView acknowledge(UUID id, long version, String note, UUID userId);
}
