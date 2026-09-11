package com.stockflow.common.security;

/**
 * The dimension an action-per-screen permission model does NOT cover: <b>which rows</b>.
 *
 * <p>"Can this user create a stock adjustment?" and "can this user see warehouse B's stock?" are
 * different questions. A permission matrix answers the first. Without the second, a warehouse
 * clerk in Ho Chi Minh City can list the Hanoi warehouse, and a customer can read another
 * customer's orders - both through endpoints they are legitimately allowed to call.</p>
 *
 * <p>Scope is therefore stored alongside the permission, not instead of it, and is applied as a
 * mandatory filter in the query layer.</p>
 */
public enum DataScope {

    /** Only rows the user owns: their own orders, their own designs, their own advances. */
    OWN,

    /** Rows belonging to the user's team or department. */
    TEAM,

    /** Rows belonging to the warehouses the user is assigned to. */
    WAREHOUSE,

    /** Everything. Should be rare and deliberate. */
    ALL;

    public boolean isBroaderThan(DataScope other) {
        return this.ordinal() > other.ordinal();
    }
}
