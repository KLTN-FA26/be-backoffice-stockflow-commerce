package com.stockflow.customer.api;

/** What {@link SalesOwnership#claimIfUnowned} did (BR-S05). */
public enum ClaimOutcome {
    /** The customer had no active owner; the claimer is its owner now and the managers are told. */
    CLAIMED,
    /** The claimer already owns it: nothing changed. */
    ALREADY_OWNER,
    /** The claimer stands in for the active owner today: nothing changed, the claimer may act for them. */
    COVERING
}
