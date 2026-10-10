package com.stockflow.inventory.internal.domain;

/**
 * "May stock be put here?" — asked before stock is moved to a location, so a typo is a 404 and a
 * blocked bin is a 409, each with a real error code, rather than a foreign-key refusal from the
 * ledger insert or, worse, a move the ledger records into a place nobody may use (issue #67).
 *
 * <p>A port because the answer belongs to {@code warehouse}: a location's own status is narrowed by
 * its shelf's and its warehouse's (issue #18 D2), and only {@code warehouse :: api} computes that.</p>
 */
public interface LocationDirectory {

    /** What {@code warehouse} says about a location code, for a decision inventory makes. */
    enum LocationState {
        /** No location has this code. */
        UNKNOWN,
        /** It exists, but it, its shelf or its warehouse is BLOCKED, in MAINTENANCE or INACTIVE. */
        UNUSABLE,
        USABLE
    }

    LocationState stateOf(LocationId location);
}
