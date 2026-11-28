package com.stockflow.customer.api;

/** Why {@link Responsible#actingUserId} is the one who acts (sales-owner plan §2.2). */
public enum ResponsibleKind {
    /** The customer's owner, an active account with no cover today. */
    OWNER,
    /** The owner's stand-in: a cover is in force today (BR-S07). Show "on behalf of the owner". */
    COVER,
    /**
     * No active owner (never had one, left, or locked): the active Sales Managers, else the System Admins (D6, case 25).
     * Never every salesperson. Show "needs an owner".
     */
    MANAGERS
}
