package com.stockflow.inventory.internal.domain;

/**
 * The life of a stock package ({@code ck_stock_package_status}, V20261020000100):
 * <pre>
 *   RECEIVING ──▶ IN_STOCK ──▶ CONSUMED       (emptied by picks, issues, scrap)
 *       │            │  ▲
 *       ▼            ▼  │
 *     VOID          LOST
 * </pre>
 * Only {@link #IN_STOCK} holds stock; every other status holds none.
 */
public enum StockPackageStatus {

    /** Labelled and being counted on a receipt not yet posted. Holds no stock. */
    RECEIVING,

    /** Counted in; its stock rows hold what is left in it. */
    IN_STOCK,

    /** Emptied. Kept for its history. */
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
