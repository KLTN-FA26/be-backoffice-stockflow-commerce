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

    /**
     * The refund of goods that came back: UPDATE proposes (Sales), APPROVE approves it - four eyes, the
     * Accountant (V20261101020000). Counting a returned parcel in ({@code sales-rma-receipts}) and QC's verdict
     * ({@code sales-rma-inspections}) went with team plan 4D-3: goods come back on a return receipt
     * ({@code procurement-goods-receipts}) and nobody inspects them (V20261114000200).
     */
    public static final String RMA_RESOLUTIONS = "sales-rma-resolutions";

    private OrderResources() {
    }
}
