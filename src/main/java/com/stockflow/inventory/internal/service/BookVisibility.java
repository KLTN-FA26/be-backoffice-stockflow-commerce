package com.stockflow.inventory.internal.service;

/**
 * Whether the reader of a stock screen may see the book quantity of a slot under a cycle count (SCRUM-146, owner
 * decision of 28/10): only who holds {@code inventory-cycle-counts:APPROVE} does. For everyone else the count is blind
 * in the whole system, not just on the count screen - the on-hand of such a slot, its ledger and its adjustments
 * (a pending variance is a delta against the books) would hand the counter the figure they are meant to find.
 *
 * <p>A reader is classified at the controller, by permission and never by role name, and passed down explicitly: an
 * ambient default would hide stock from the jobs and in-process callers that read these services with no caller at
 * all.</p>
 */
public enum BookVisibility {

    /** Everything: for who may decide a count, and for in-process callers. */
    FULL,

    /** Rows of a slot a count holds (open, or its variance still pending) show no quantity, or are left out. */
    HIDE_COUNTED_SLOTS;

    /** @param mayDecideCounts the caller holds {@code inventory-cycle-counts:APPROVE} */
    public static BookVisibility forCaller(boolean mayDecideCounts) {
        return mayDecideCounts ? FULL : HIDE_COUNTED_SLOTS;
    }

    public boolean hidesCountedSlots() {
        return this == HIDE_COUNTED_SLOTS;
    }
}
