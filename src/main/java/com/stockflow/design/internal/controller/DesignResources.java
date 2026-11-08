package com.stockflow.design.internal.controller;

/** Stable permission identifiers, independent of frontend routes. */
public final class DesignResources {
    public static final String DESIGNS = "design-designs";
    /** Seeded since V20260903000100; granted to SALES_STAFF by V20261017000800. */
    public static final String SNAPSHOTS = "design-snapshots";
    /** Customers' quote requests on their designs; seeded by V20261108001100. */
    public static final String QUOTE_REQUESTS = "design-quote-requests";
    private DesignResources() { }
}
