package com.stockflow.inventory.internal.domain;

import java.time.LocalDate;
import java.util.Optional;

/**
 * When a lot counts as expired or near expiry (BR-STK-004, flow F-EXP, docs 03 BR-06). Dates are business
 * dates (Vietnam); "today" is the date the sweep runs for.
 *
 * <pre>
 *   expiry &lt; today                 EXPIRED   leaves ATP at once, moved to EXPIRED by the sweep
 *   today &lt;= expiry &lt;= today+N     EXPIRING  only warns, nothing is blocked
 *   expiry &gt; today+N               nothing
 * </pre>
 *
 * <p>A lot is still sellable on the day it expires: ATP reads {@code expiry_date >= today}.</p>
 */
public final class ExpiryPolicy {

    private ExpiryPolicy() {
    }

    public static boolean isExpired(LocalDate expiryDate, LocalDate today) {
        return expiryDate != null && expiryDate.isBefore(today);
    }

    /** Within {@code warningDays} days, today included, and not expired yet. */
    public static boolean isNearExpiry(LocalDate expiryDate, LocalDate today, int warningDays) {
        return expiryDate != null && !expiryDate.isBefore(today) && !expiryDate.isAfter(today.plusDays(warningDays));
    }

    /** The alert kind a lot with this expiry date calls for today, if any. */
    public static Optional<StockAlert.Kind> alertKind(LocalDate expiryDate, LocalDate today, int warningDays) {
        if (isExpired(expiryDate, today)) {
            return Optional.of(StockAlert.Kind.EXPIRED);
        }
        return isNearExpiry(expiryDate, today, warningDays) ? Optional.of(StockAlert.Kind.EXPIRING) : Optional.empty();
    }

    /**
     * Whether the sweep may move a stock row to EXPIRED: only when no customer's hold is on it. The
     * aggregate refuses to block reserved stock (it would let an order believe it still has goods), so a
     * lot with holds stays as it is and its alert says so ({@code reservation_impaired}).
     */
    public static boolean canBlock(int reserved) {
        return reserved == 0;
    }
}
