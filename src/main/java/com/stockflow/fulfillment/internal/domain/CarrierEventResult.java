package com.stockflow.fulfillment.internal.domain;

/**
 * What came of a call to a carrier's webhook, or of a line of an imported file of outcomes (kltn-docs 09 §4.5,
 * BR-27). Decided once; the error list's results
 * ({@link #errorList()}) are resolved by hand and never applied again.
 */
public enum CarrierEventResult {

    /** Stored, being handled. One still here after a while is a crash in between: the error list shows it. */
    RECEIVED,
    /** It changed the load or a shipment, or recorded a milestone. */
    APPLIED,
    /** The same report again: recorded once. */
    DUPLICATE,
    /** Older than what was last applied to the same load or shipment, or about an order the load no longer has. */
    STALE,
    /** Nothing in it for the system. */
    IGNORED,
    /** No live load of this carrier to match it to (BR-22). */
    UNMATCHED,
    /** Its signature or credential did not hold, or it named no carrier the system knows. */
    INVALID_SIGNATURE,
    /** Not valid for the state of the load or shipment. */
    NEEDS_REVIEW;

    /** The warehouse manager's error list (09 §4.5 step 8). */
    public boolean errorList() {
        return this == RECEIVED || this == UNMATCHED || this == INVALID_SIGNATURE || this == NEEDS_REVIEW;
    }
}
