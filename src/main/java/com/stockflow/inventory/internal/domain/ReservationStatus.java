package com.stockflow.inventory.internal.domain;

/**
 * Lifecycle of one hold on stock (BRD 3.14.5).
 *
 * <pre>
 *   HELD ──consume──▶ CONSUMED     (goods picked, stock deducted for real)
 *     │
 *     ├──allocate───▶ ALLOCATED    (a SKU reservation only: the order was released to a warehouse and
 *     │                             bins and lots were chosen; the hold lives on as allocations)
 *     ├──release────▶ RELEASED     (order cancelled / payment failed / manual override)
 *     └──sweep──────▶ EXPIRED      (the hold outlived its expiresAt)
 * </pre>
 *
 * <p>Two kinds of row share this lifecycle (docs 01, "Reserved" vs "Allocated"). A {@link SkuReservation}
 * is the soft hold taken at checkout against a SKU, with no location: HELD, then ALLOCATED, RELEASED or
 * EXPIRED. A {@link Reservation} sits on one stock item - an allocation, a production hold, or a checkout hold
 * made before SKU reservations existed: HELD, then CONSUMED, RELEASED or EXPIRED. ALLOCATED is never the state
 * of a stock-item hold ({@code ck_stock_reservation_allocated_is_soft}).</p>
 *
 * <p>Only {@link #HELD} is a live hold; the others are terminal and exist so that the row
 * survives as an audit trail instead of being deleted.</p>
 */
public enum ReservationStatus {

    HELD,
    /** A SKU reservation turned into allocations on specific stock items (F-FUL step 02). */
    ALLOCATED,
    CONSUMED,
    RELEASED,
    EXPIRED;

    public boolean isActive() {
        return this == HELD;
    }

    public boolean isTerminal() {
        return !isActive();
    }
}
