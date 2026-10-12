package com.stockflow.production.internal.controller;

/**
 * Permission resource codes owned by the production module, as seeded by V20261010000500 and V20261014000400.
 *
 * <p>Constants because annotation arguments must be compile-time constants; each appears once on the
 * {@code @PermissionResource} that declares it and once per guarded endpoint, and
 * {@code PermissionCatalogValidator} fails startup when a guard names an undeclared resource.</p>
 */
public final class ProductionResources {

    public static final String PRODUCTION_ORDERS = "production-orders";
    public static final String SAMPLE_REQUESTS = "production-sample-requests";
    /** V20261014000400: blanks issued to the print shop, a warehouse job (Jira 397). */
    public static final String MATERIAL_ISSUES = "production-material-issues";

    private ProductionResources() {
    }
}
