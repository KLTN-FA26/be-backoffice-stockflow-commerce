package com.stockflow.order.internal.controller;

/** Permission resource codes owned by the order module. */
public final class OrderResources {

    /** Hyphens only: {@code PermissionCode} enforces {@code [a-z0-9-]{2,64}}. */
    public static final String ORDERS = "sales-orders";

    /**
     * Cancelling someone else's order from the back office (kltn-docs 17 §2, §4.5). Its own resource
     * so Sales can cancel without also being able to release orders, which {@link #RELEASES} guards (SCRUM-256,
     * SCRUM-459).
     */
    public static final String ORDER_CANCELLATIONS = "sales-order-cancellations";

    /**
     * Releasing an order to a warehouse (kltn-docs 17 BR-03): the warehouse manager of that warehouse, so it is
     * checked against the caller's warehouses too. Split out of {@code sales-orders:APPROVE} with {@link #HOLDS}
     * when the order coordinator role was retired (QD-12, team plan 5-7), so that neither side holds the other's
     * work. Seeded by V20261115000200; team plan 4B-8 releases by delivery batch with it.
     */
    public static final String RELEASES = "sales-order-releases";

    /** Putting an order on hold and resolving the hold (17 §5): Sales' (QD-12). Seeded by V20261115000200. */
    public static final String HOLDS = "sales-order-holds";

    /**
     * Fulfillment's resource, declared there: recording that an order without shipments was delivered or was not
     * answers to the same {@code :APPROVE} as a shipment's outcome (09 BR-02) - the warehouse manager's since team
     * plan 5-7 (17 §2, BR-27).
     */
    public static final String DELIVERY_OUTCOMES = "fulfillment-delivery-outcomes";

    /** Orders over their customer's credit limit, waiting for whoever approves credit (SCRUM-427). */
    public static final String CREDIT_HOLDS = "sales-order-credit-holds";

    /** B2B quotes (SCRUM-448), seeded by V20261017000300. */
    public static final String QUOTES = "sales-quotes";

    /**
     * Turning an accepted quote into its order (decision 2026-10-17): Sales'. Its own resource so neither
     * {@code sales-quotes:UPDATE} (editing) nor {@code sales-orders:CREATE} (which the portal customer holds) has to
     * stand for it. Seeded by V20261017000300.
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
     * How accepted units are made good: UPDATE proposes (Sales), APPROVE approves a resolution with money - four eyes,
     * the Accountant (V20261101020000).
     */
    public static final String RMA_RESOLUTIONS = "sales-rma-resolutions";

    private OrderResources() {
    }
}
