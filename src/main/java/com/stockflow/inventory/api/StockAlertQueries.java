package com.stockflow.inventory.api;

import java.util.List;

/**
 * The open expiry alerts (SCRUM-165/166) for other modules: the lots the nightly sweep found expiring within the
 * warning window ({@link InventoryControlService#expiryWarningDays()}) or already expired, and that nobody has
 * resolved yet.
 *
 * <p>Alerts rather than a fresh scan of stock, so the answer is the list the back office's alert screen shows, and
 * the two cannot disagree. The cost is that a lot only appears once the sweep has seen it.</p>
 *
 * <p><b>Scope.</b> The caller's warehouse limit is applied exactly as on {@code GET /api/v1/inventory/alerts}: a
 * warehouse-limited caller sees the alerts of their own warehouses. Call it inside the same guard as that endpoint,
 * {@code @RequiresPermission(resource = "inventory-alerts", action = READ)}.</p>
 */
public interface StockAlertQueries {

    /** The most alerts one call returns. */
    int MAX_ALERTS = 200;

    /**
     * Open and acknowledged EXPIRING and EXPIRED alerts, soonest expiry first.
     *
     * @param limit 1..{@value #MAX_ALERTS}
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a limit out of range
     */
    List<ExpiryAlertView> openExpiryAlerts(int limit);
}
