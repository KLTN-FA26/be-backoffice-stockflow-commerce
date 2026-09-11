package com.stockflow.inventory.internal.domain;

/**
 * Lifecycle of one hold on stock (BRD 3.14.5).
 *
 * <pre>
 *   HELD ──consume──▶ CONSUMED     (goods picked, stock deducted for real)
 *     │
 *     ├──release────▶ RELEASED     (order cancelled / payment failed / manual override)
 *     └──sweep──────▶ EXPIRED      (the hold outlived its expiresAt)
 * </pre>
 *
 * <p>Only {@link #HELD} is a live hold; the other three are terminal and exist so that the row
 * survives as an audit trail instead of being deleted.</p>
 */
public enum ReservationStatus {

    HELD,
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
