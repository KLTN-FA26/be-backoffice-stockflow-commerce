package com.stockflow.production.internal.controller;

/**
 * Permission resource codes owned by the production module, as seeded by V20261010000500, V20261017000400 and V20261017000420.
 *
 * <p>Constants because annotation arguments must be compile-time constants; each appears once on the
 * {@code @PermissionResource} that declares it and once per guarded endpoint, and
 * {@code PermissionCatalogValidator} fails startup when a guard names an undeclared resource.</p>
 */
public final class ProductionResources {

    public static final String PRODUCTION_ORDERS = "production-orders";
    public static final String SAMPLE_REQUESTS = "production-sample-requests";
    /** V20261017000400: blanks issued to the print shop, a warehouse job (Jira 397). */
    public static final String MATERIAL_ISSUES = "production-material-issues";
    /** V20261017000420: QC rounds of printed goods (03 §16 actor QC staff). */
    public static final String QC_RESULTS = "production-qc-results";

    private ProductionResources() {
    }
}
