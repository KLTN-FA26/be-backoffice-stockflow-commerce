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

    /** The most alerts of each kind one call returns. */
    int MAX_ALERTS = 200;

    /**
     * Open and acknowledged expiry alerts, soonest expiry first, EXPIRING and EXPIRED apart.
     *
     * <p>Apart, and each with its own total, because an EXPIRED alert stays open until its write-off is approved
     * (BR-STK-005): in one list capped at a limit, a backlog of expired lots pushed out every lot that could still be
     * sold or used first, which are the ones worth acting on.</p>
     *
     * @param withinDays EXPIRING alerts at most this many days from expiry; an alert without a date is kept
     * @param limit      1..{@value #MAX_ALERTS}, for each list
     * @throws com.stockflow.common.error.BusinessException {@code VALIDATION_FAILED} for a limit or a window out of
     *         range
     */
    ExpiryAlerts openExpiryAlerts(int withinDays, int limit);

    /** @param expiringTotal how many EXPIRING alerts match; {@code expiredTotal} likewise for EXPIRED */
    record ExpiryAlerts(List<ExpiryAlertView> expiring, int expiringTotal, List<ExpiryAlertView> expired,
                        int expiredTotal) {
    }
}
