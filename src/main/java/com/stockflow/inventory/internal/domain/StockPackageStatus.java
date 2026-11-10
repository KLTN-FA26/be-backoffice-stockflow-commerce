package com.stockflow.inventory.internal.domain;

/**
 * The life of a stock package ({@code ck_stock_package_status}, V20261020000100; kltn-docs 20 §5.5, the way back from
 * CONSUMED since V20261111000200):
 * <pre>
 *   RECEIVING ──▶ IN_STOCK ◀──▶ CONSUMED      (left stock whole: picked for an order, or written off whole;
 *       │          │  ▲                       back whole: put back for a cancelled order, or returned)
 *       ▼          ▼  │
 *     VOID         LOST
 * </pre>
 * Only {@link #IN_STOCK} holds stock; every other status holds none.
 */
public enum StockPackageStatus {

    /** Labelled and being counted on a receipt not yet posted. Holds no stock. */
    RECEIVING,

    /** Counted in; its stock rows hold what is left in it. */
    IN_STOCK,

    /** Left stock whole. Kept for its history, and for the day it comes back whole (put back, or returned). */
    CONSUMED,

    /** A count did not find it; it may be found again. */
    LOST,

    /** Its receipt was cancelled before posting. */
    VOID;

    /** Closed: has a {@code closed_at} ({@code ck_stock_package_closed}). */
    public boolean isClosed() {
        return this == CONSUMED || this == LOST || this == VOID;
    }
}
