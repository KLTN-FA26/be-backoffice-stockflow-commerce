package com.stockflow.contracts;

/**
 * Why a customer's Sales owner changed (the sales-owner plan of 2026-11-28, BR-S03). Stored by name in
 * {@code customer.customer_sales_owner_history.reason} and carried by {@link CustomerSalesOwnerChanged}.
 *
 * <p>One enum, here rather than a copy inside {@code customer}: the reason is the history's own vocabulary, which the
 * listeners of a change (chat's internal note, the notices) print as it is, so a value the module had and the event
 * lacked would be a reason nobody downstream could read. Adding a value is a contract change; renaming one is not
 * allowed (history rows and waiting publications keep the name).</p>
 */
public enum SalesOwnerChangeReason {
    /** The Sales who created the customer became its owner, or a manager chose one at creation (BR-S02). */
    CREATED_BY_SALES,
    /** The customer came from a lead; its owner is the lead's Sales. */
    LEAD_CONVERTED,
    /** A Sales claimed a conversation of a customer with no active owner (BR-S05). */
    CLAIMED_FROM_QUEUE,
    /** The owner left or was locked out (BR-S08): marking the customer, or a manager moving it on. */
    STAFF_LEFT,
    WORKLOAD,
    CUSTOMER_REQUEST,
    REGION,
    /** A temporary transfer while the owner is away for long (plan §10.1); its batch can be returned. */
    TEMPORARY_LEAVE,
    /** The return of a temporary transfer to the original owner. */
    RETURN_FROM_LEAVE,
    /** Filled by the migration from the newest sent document (plan §4 case 28). */
    BACKFILL,
    /** Anything else; the note says what (required). */
    OTHER;

    /**
     * Reasons the system writes on its own: a manager changing an owner by hand never picks one of them (the change
     * screen offers STAFF_LEFT, WORKLOAD, CUSTOMER_REQUEST, REGION, TEMPORARY_LEAVE and OTHER).
     */
    public boolean systemOnly() {
        return this == CREATED_BY_SALES || this == LEAD_CONVERTED || this == CLAIMED_FROM_QUEUE
                || this == RETURN_FROM_LEAVE || this == BACKFILL;
    }
}
