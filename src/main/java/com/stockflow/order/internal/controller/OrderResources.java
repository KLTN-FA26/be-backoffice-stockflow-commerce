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
     * 4B-4 (kltn-docs 14 §4.3): the sales contract of an order - Sales draws it up, edits, sends and records it signed
     * (APPROVE); accounting and the Sales Manager read it. Seeded by V20261108002200.
     */
    public static final String SALES_CONTRACTS = "sales-contracts";

    /**
     * PACK-PARTIAL (lead's decision 2026-10-21): Sales decides whether an order may ship in parts. Its own resource,
     * not {@code sales-orders:UPDATE}, which the portal customer holds for their own orders. Seeded by V20261022000400
     * for SALES_STAFF (and SYSTEM_ADMIN, who holds everything).
     */
    public static final String PARTIAL_SHIPMENTS = "sales-order-partial-shipments";

    /**
     * 4B-7 (kltn-docs 17 §4.2 steps 2-3): the warehouse an order of a contract ships from, and how much of its stock is
     * held there - UPDATE for Sales, who chooses it; READ for those who follow the order. Not
     * {@code sales-orders:UPDATE}, which the portal customer holds for their own orders.
     */
    public static final String SHIPPING_WAREHOUSES = "sales-order-shipping-warehouses";

    /**
     * 4A-1 (kltn-docs 17 §4.3): the producer catalogue - kept by the Production Coordinator (00 §2.1, 2026-11-07), read
     * by the Sales Manager, Sales and the warehouse manager. A new code: the old {@code production-*} codes went with
     * in-house production (V20261108000100).
     */
    public static final String PRODUCERS = "sales-producers";

    /**
     * 4A-2 (kltn-docs 17 §4.3, BR-23): production orders - the Production Coordinator draws up, edits, submits, cancels
     * and closes (CREATE, UPDATE); the Sales Manager approves and returns (APPROVE); Sales and the warehouse manager read.
     */
    public static final String PRODUCTION_ORDERS = "production-orders";

    /**
     * Return requests (SCRUM-243, seeded by V20260903000100, UPDATE added by V20261101020000): CREATE opens one and adds
     * photos (the customer on their own orders, Sales on any), UPDATE withdraws one, READ lists them, APPROVE is Sales'
     * review (approve, reject).
     */
    public static final String RMAS = "sales-rmas";

    /** Counting a returned parcel into quarantine: the warehouse's step (V20261101020000). */
    public static final String RMA_RECEIPTS = "sales-rma-receipts";

    /** QC's verdict on returned goods and where they go (V20261101020000). */
    public static final String RMA_INSPECTIONS = "sales-rma-inspections";

    /**
     * How accepted units are made good: UPDATE proposes (Sales, coordinator), APPROVE approves a resolution with money
     * - four eyes, the Accountant (V20261101020000).
     */
    public static final String RMA_RESOLUTIONS = "sales-rma-resolutions";

    private OrderResources() {
    }
}
