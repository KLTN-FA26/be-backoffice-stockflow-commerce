package com.stockflow.common.security;

/**
 * String constants for the ten roles.
 *
 * <p>This class exists ONLY because Java annotation arguments must be compile-time constants:
 * {@code @PreAuthorize("hasAnyAuthority('" + Roles.QC_STAFF + "')")} compiles,
 * {@code Role.QC_STAFF.name()} does not. Everywhere else, prefer the {@link Role} enum.</p>
 */
public final class Roles {

    public static final String WAREHOUSE_STAFF     = "WAREHOUSE_STAFF";
    public static final String WAREHOUSE_MANAGER   = "WAREHOUSE_MANAGER";
    public static final String INVENTORY_PLANNER   = "INVENTORY_PLANNER";
    public static final String QC_STAFF            = "QC_STAFF";
    public static final String CUSTOMER            = "CUSTOMER";
    public static final String SALES_STAFF         = "SALES_STAFF";
    public static final String ORDER_COORDINATOR   = "ORDER_COORDINATOR";
    public static final String ECOMMERCE_ADMIN     = "ECOMMERCE_ADMIN";
    public static final String PROCUREMENT_STAFF   = "PROCUREMENT_STAFF";
    public static final String ACCOUNTANT          = "ACCOUNTANT";

    private Roles() {
    }
}
