package com.stockflow.inventory.internal.service;

import com.stockflow.common.security.Action;
import com.stockflow.common.security.CurrentUser;

import java.util.Optional;

/**
 * Whether the reader of a stock screen may see the book quantity of a slot under a cycle count (SCRUM-146, owner
 * decision of 28/10): only who holds {@code inventory-cycle-counts:APPROVE} does. For everyone else the count is blind
 * in the whole system, not just on the count screen - the on-hand of such a slot, its ledger and its adjustments
 * (a pending variance is a delta against the books) would hand the counter the figure they are meant to find
 * (kltn-docs 20 BR-07).
 *
 * <p>A reader is classified where a person's read enters the module - a controller, or a published query that is
 * documented as one person's read under a screen's guard ({@code LotTraceQueries}, {@code StockAdjustmentQueries},
 * {@code BlindCountQueries}) - and passed down explicitly. Never as an ambient default inside a shared service: that
 * would hide stock from the jobs and in-process callers that read these services with no caller at all.</p>
 */
public enum BookVisibility {

    /** Everything: for who may decide a count, and for in-process callers. */
    FULL,

    /** Rows of a slot a count holds (open, or its variance still pending) show no quantity, or are left out. */
    HIDE_COUNTED_SLOTS;

    /**
     * The permission resource whose APPROVE decides a count: {@code InventoryResources.CYCLE_COUNTS}, repeated here
     * because the application layer does not reach into the web layer; {@code BookVisibilityTest} pins the two equal.
     */
    static final String CYCLE_COUNTS = "inventory-cycle-counts";

    /** @param mayDecideCounts the caller holds {@code inventory-cycle-counts:APPROVE} */
    public static BookVisibility forCaller(boolean mayDecideCounts) {
        return mayDecideCounts ? FULL : HIDE_COUNTED_SLOTS;
    }

    /**
     * What {@code caller} may see, asked by permission, so a custom role with the grant is covered and a role name
     * never is. An absent caller is blind: every place that classifies a reader is guarded by a permission, so in a
     * secured deployment there always is one, and where there is none (security switched off in tests) hiding a slot's
     * books is the side that cannot leak. Fail closed.
     */
    public static BookVisibility of(Optional<CurrentUser> caller) {
        return forCaller(caller.map(user -> user.can(CYCLE_COUNTS, Action.APPROVE)).orElse(false));
    }

    public boolean hidesCountedSlots() {
        return this == HIDE_COUNTED_SLOTS;
    }
}
