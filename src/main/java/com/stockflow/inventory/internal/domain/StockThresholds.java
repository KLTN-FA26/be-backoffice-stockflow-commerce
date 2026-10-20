package com.stockflow.inventory.internal.domain;

/**
 * The one definition of "low stock" (plan A8): what the threshold comparison means, written once for
 * {@code evaluate(sku)}, the alert evaluator, the nightly reconcile and the dashboard that will list low
 * stock ({@code InventoryService.lowStockBatch}).
 *
 * <p>The quantity compared is <b>usable on-hand</b>: on-hand of AVAILABLE stock that has not expired by the
 * business date, reservations <i>not</i> subtracted - not ATP (SCRUM-70/71 change request, open question
 * C11: safety stock is only for alerting, it never reduces what can be sold). A threshold that is
 * {@code null} is "not configured", which is neither false nor zero: the answer is {@code null}.</p>
 */
public final class StockThresholds {

    private StockThresholds() {
    }

    /** At or below the reorder point: time to raise a purchase order (glossary: reorder point). */
    public static Boolean reorderRequired(long usableOnHand, Integer reorderPoint) {
        return reorderPoint == null ? null : usableOnHand <= reorderPoint;
    }

    /** Strictly below the safety stock: under the level the business wants to keep. */
    public static Boolean belowSafetyStock(long usableOnHand, Integer safetyStock) {
        return safetyStock == null ? null : usableOnHand < safetyStock;
    }

    /** Whether either threshold is crossed; what makes a SKU "low stock" for a list. */
    public static boolean isLow(long usableOnHand, Integer reorderPoint, Integer safetyStock) {
        return Boolean.TRUE.equals(reorderRequired(usableOnHand, reorderPoint))
                || Boolean.TRUE.equals(belowSafetyStock(usableOnHand, safetyStock));
    }
}
