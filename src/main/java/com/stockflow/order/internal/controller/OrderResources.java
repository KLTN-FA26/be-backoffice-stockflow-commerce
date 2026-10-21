package com.stockflow.order.internal.controller;

/** Permission resource codes owned by the order module. */
public final class OrderResources {

    /** Hyphens only: {@code PermissionCode} enforces {@code [a-z0-9-]{2,64}}. */
    public static final String ORDERS = "sales-orders";

    /**
     * Cancelling someone else's order from the back office (kltn-docs 17 §2, §4.5). Its own resource
     * so Sales can cancel without also being able to release orders to production, which
     * {@code sales-orders:APPROVE} guards (SCRUM-256, SCRUM-459).
     */
    public static final String ORDER_CANCELLATIONS = "sales-order-cancellations";

    /** Orders over their customer's credit limit, waiting for whoever approves credit (SCRUM-427). */
    public static final String CREDIT_HOLDS = "sales-order-credit-holds";

    /** B2B quotes (SCRUM-448), seeded by V20261017000300. */
    public static final String QUOTES = "sales-quotes";

    /**
     * Turning an accepted quote into its order (decision 2026-10-17): Sales' and the order coordinator's, who does not
     * edit quotes. Its own resource so neither {@code sales-quotes:UPDATE} (editing) nor {@code sales-orders:CREATE}
     * (which the portal customer holds) has to stand for it. Seeded by V20261017000300.
     */
    public static final String QUOTE_CONVERSIONS = "sales-quote-conversions";

    /**
     * PACK-PARTIAL (lead's decision 2026-10-21): Sales decides whether an order may ship in parts. Its own resource,
     * not {@code sales-orders:UPDATE}, which the portal customer holds for their own orders. Seeded by V20261022000400
     * for SALES_STAFF (and SYSTEM_ADMIN, who holds everything).
     */
    public static final String PARTIAL_SHIPMENTS = "sales-order-partial-shipments";

    private OrderResources() {
    }
}
