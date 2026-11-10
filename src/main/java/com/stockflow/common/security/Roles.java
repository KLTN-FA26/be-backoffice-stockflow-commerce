package com.stockflow.common.security;

/**
 * String constants for the roles.
 *
 * <p>This class exists ONLY because Java annotation arguments must be compile-time constants:
 * {@code @PreAuthorize("hasAnyAuthority('" + Roles.WAREHOUSE_STAFF + "')")} compiles,
 * {@code Role.WAREHOUSE_STAFF.name()} does not. Everywhere else, prefer the {@link Role} enum.</p>
 */
public final class Roles {

    public static final String WAREHOUSE_STAFF     = "WAREHOUSE_STAFF";
    public static final String WAREHOUSE_MANAGER   = "WAREHOUSE_MANAGER";
    public static final String CUSTOMER            = "CUSTOMER";
    public static final String SALES_STAFF         = "SALES_STAFF";
    /** Was ECOMMERCE_ADMIN until V20261107000100 renamed it (kltn-docs 00 §2, 2026-11-05). Same id, same grants. */
    public static final String SALES_MANAGER       = "SALES_MANAGER";
    public static final String PROCUREMENT_STAFF   = "PROCUREMENT_STAFF";
    public static final String ACCOUNTANT          = "ACCOUNTANT";
    public static final String SYSTEM_ADMIN        = "SYSTEM_ADMIN";

    private Roles() {
    }
}
