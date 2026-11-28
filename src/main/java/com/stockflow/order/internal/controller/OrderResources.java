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
     * The customer module's resource, declared there ({@code SalesWorkloadResourceDeclaration}, V20270128000140): the
     * Sales Managers' workload board, served by this module because most of its figures are orders' documents.
     */
    public static final String SALES_WORKLOAD = "sales-workload";

    /**
     * Reading an order's delivery schedule (17 §4.5, BR-29): the batches, the window in force, what is held and released.
     * Its own resource, not {@code sales-contracts:READ}, which opens the contract itself: the warehouse manager releases
     * batch by batch and must see the schedule without seeing the contract (WF4 BE-13). Held by everyone who read the
     * schedule before and by whoever releases ({@link #RELEASES}); seeded by V20261124000650. Window changes stay
     * {@code sales-contracts:UPDATE}.
     */
    public static final String DELIVERY_BATCHES = "sales-order-delivery-batches";

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
     * Approving or rejecting the prices of a quote held for approval (kltn-docs 14 BR-10 as changed on 2026-11-26): the
     * Sales Manager's. Its own resource: neither editing quotes ({@code sales-quotes:UPDATE}, Sales') nor the customer's
     * answer ({@code sales-quotes:APPROVE}, the portal's) stands for it. Seeded by V20261230000200.
     */
    public static final String QUOTE_PRICE_APPROVALS = "sales-quote-price-approvals";

    /**
     * 4B-4 (kltn-docs 14 §4.3): the sales contract of an order - Sales draws it up, edits, sends and records it signed
     * (APPROVE); accounting and the Sales Manager read it. Seeded by V20261108002200.
     */
    public static final String SALES_CONTRACTS = "sales-contracts";

    /**
     * Approving or rejecting the non-standard terms of a draft contract before it is sent (QĐ-4 of the contract e-signing
     * plan, 2026-11-28): the Sales Manager's. Its own resource: Sales draw the contract up ({@code sales-contracts:UPDATE})
     * and may not approve their own deviations. Seeded by V20270129000110.
     */
    public static final String SALES_CONTRACT_TERMS = "sales-contract-terms";

    /**
     * Signing a contract for the company once the customer has signed (QĐ-2): the Sales Manager's and the System Admin's,
     * never the account that drew the contract up or sent it. Seeded by V20270129000110, with the signing machinery.
     */
    public static final String SALES_CONTRACT_SIGNATURES = "sales-contract-signatures";

    /**
     * PACK-PARTIAL (lead's decision 2026-10-21): Sales decides whether an order may ship in parts. Its own resource,
     * not {@code sales-orders:UPDATE}, which the portal customer holds for their own orders. Seeded by V20261022000400
     * for SALES_STAFF (and SYSTEM_ADMIN, who holds everything).
     */
    public static final String PARTIAL_SHIPMENTS = "sales-order-partial-shipments";

    /**
     * WF04 G2 (kltn-docs 17 §4.7 steps 23-24, BR-16; 08 BR-04): Sales change the delivery address of an order until its
     * first carton is labelled. Its own resource, not {@code sales-orders:UPDATE}, which the portal customer holds for
     * their own orders: the customer asks, Sales changes it. Seeded by V20261204006100 for SALES_STAFF (and
     * SYSTEM_ADMIN, who holds everything).
     */
    public static final String ADDRESSES = "sales-order-addresses";

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
     * 4A-2 (kltn-docs 17 §4.3): production orders - the Production Coordinator draws up, edits, sends, cancels and
     * closes (CREATE, UPDATE); nobody approves one since V20261213000200, which retired APPROVE; Sales, the Sales
     * Manager and the warehouse manager read.
     */
    public static final String PRODUCTION_ORDERS = "production-orders";

    /**
     * Those told when a production order is sent (V20261213000200; kltn-docs 17 §2): the Sales Manager and the
     * administrator, oversight after the fact now that nobody approves one. An audience only, guarding no endpoint;
     * split from {@link #PRODUCTION_ORDERS} because its READ is held too widely to be who is told.
     */
    public static final String PRODUCTION_ORDER_SENDS = "production-order-sends";

    /**
     * 4A-4 (kltn-docs 13 §4.3 step 5, BR-13): the storefront's print samples to approve - the customer reads and
     * answers their own (scope OWN, the owner checked by the service). The coordinator's side of the rounds is
     * {@code production-orders:UPDATE}. Seeded by V20261108003100.
     */
    public static final String PRODUCTION_SAMPLES = "production-samples";

    /**
     * 4A-7 (kltn-docs 17 §4.12 step 47): the production schedule - read by the Production Coordinator, Sales, the Sales
     * Manager and the warehouse manager (their warehouses). Its holders with a business-wide scope are the ones told of
     * AT_RISK, PRODUCTION_LATE and PO_NEEDED (BR-30).
     */
    public static final String PRODUCTION_SCHEDULE = "production-schedule";

    /**
     * 4A-7 (17 §4.12 step 49): the delay flags of an order, for the order page - Sales, the Sales Manager, the
     * warehouse manager and (V20261209000400) the Production Coordinator. DELIVERY_LATE is listed only to holders of
     * {@link #DELIVERY_DELAY_ALERTS} too.
     */
    public static final String DELAY_ALERTS = "delay-alerts";

    /**
     * Those who follow delivery (V20261209000400; 17 §3, BR-30): they see DELIVERY_LATE in the alert list, and its
     * holders who can see the order's shipping warehouse are the ones told of it. Split from {@link #DELAY_ALERTS} so
     * the Production Coordinator can read the production alerts without being told of late deliveries.
     */
    public static final String DELIVERY_DELAY_ALERTS = "delivery-delay-alerts";

    /**
     * Return requests (SCRUM-243, seeded by V20260903000100, UPDATE added by V20261101020000): CREATE opens one and adds
     * photos (the customer on their own orders, Sales on any), UPDATE withdraws one, READ lists them, APPROVE is Sales'
     * review (approve, reject).
     */
    public static final String RMAS = "sales-rmas";

    /**
     * WF-05 Q-11 (audit round 2, M4): recording goods kept in custody for the customer on a return - the one write the
     * warehouse makes on a return request ("kho ghi biên nhận giữ hộ trên yêu cầu trả", docs 03 §4.2 step 10). CREATE
     * only; WAREHOUSE_STAFF, WAREHOUSE_MANAGER and SALES_STAFF (V20261223000330).
     */
    public static final String RMA_CUSTODY = "sales-rma-custody";

    /**
     * The refund of goods that came back: UPDATE proposes (Sales), APPROVE approves it - four eyes, the
     * Accountant (V20261101020000). Counting a returned parcel in ({@code sales-rma-receipts}) and QC's verdict
     * ({@code sales-rma-inspections}) went with team plan 4D-3: goods come back on a return receipt
     * ({@code procurement-goods-receipts}) and nobody inspects them (V20261114000200).
     */
    public static final String RMA_RESOLUTIONS = "sales-rma-resolutions";

    /**
     * BE1 (kltn-docs 06 §2, §4.6): the warehouse capacity board - the warehouse manager (their warehouses), Sales, the
     * Production Coordinator and the Sales Manager (V20261125000100). Owned here because the board is worked out here;
     * named for what it shows.
     */
    public static final String WAREHOUSE_CAPACITY = "warehouse-capacity";

    /**
     * WF-05 decision Q-12 (BE-17): closing the part of an order a shipment the carrier lost was to deliver - its units
     * closed and their money settled as a carrier's return is (Q-2), on the carrier's written confirmation of the loss.
     * APPROVE, an approval-level grant of its own (as recording a shipment brought back takes
     * {@code fulfillment-delivery-outcomes:APPROVE}, Q-7): Sales (V20261216000050).
     */
    public static final String LOST_SHIPMENT_CLOSURES = "sales-lost-shipment-closures";

    private OrderResources() {
    }
}
