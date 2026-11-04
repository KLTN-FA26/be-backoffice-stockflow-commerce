package com.stockflow.assistant.internal.service;

/**
 * The permission resources the tools carry: the same codes the mirrored screens' controllers declare.
 *
 * <p>Copied by value, because those constants are internal to their modules. {@code AssistantIntegrationTest.everyToolMirrorsItsScreen}
 * compares each tool's annotation with the endpoint it mirrors, so a renamed resource fails the build instead of
 * leaving a tool guarded by a permission nobody holds.</p>
 */
final class ToolPermissions {

    /** {@code InventoryResources.ALERTS}. */
    static final String INVENTORY_ALERTS = "inventory-alerts";
    /** {@code InventoryResources.STOCK_ITEMS}. */
    static final String INVENTORY_STOCK_ITEMS = "inventory-stock-items";
    /** {@code InventoryResources.STOCK_MOVEMENTS}. */
    static final String INVENTORY_STOCK_MOVEMENTS = "inventory-stock-movements";
    /**
     * {@code InventoryResources.STOCK_ADJUSTMENTS}: READ for the approval queue; APPROVE read here only to say whether
     * the caller may approve ({@link FindPendingAdjustmentsTool}).
     */
    static final String STOCK_ADJUSTMENTS = "inventory-stock-adjustments";
    /** {@code OrderResources.ORDERS}. */
    static final String SALES_ORDERS = "sales-orders";
    /** {@code PurchaseOrderResources.PURCHASE_ORDERS}. */
    static final String PURCHASE_ORDERS = "procurement-purchase-orders";
    /** {@code ReportingResources.REPORTS}. */
    static final String REPORTS = "reporting-reports";
    /** {@code FulfillmentResources.PICK_LISTS}. */
    static final String PICK_LISTS = "fulfillment-pick-lists";
    /** {@code FulfillmentResources.SHIPMENTS}. */
    static final String SHIPMENTS = "fulfillment-shipments";
    /**
     * {@code FulfillmentPermissions.TASK_OVERSIGHT} and {@code DESIGN_REVERIFICATION}: read here only to say which of
     * fulfillment's task-visibility rules applied to a caller ({@link FindFulfillmentTasksTool}).
     */
    static final String TASK_OVERSIGHT = "fulfillment-task-oversight";
    static final String DESIGN_REVERIFICATION = "fulfillment-design-reverification";
    /** {@code SupplierResources.SUPPLIER_ITEMS}. */
    static final String SUPPLIER_ITEMS = "procurement-supplier-items";
    /** {@code ProductionResources.PRODUCTION_ORDERS}. */
    static final String PRODUCTION_ORDERS = "production-orders";
    /** {@code ProductionResources.SAMPLE_REQUESTS}: read here only to decide what a production order may name. */
    static final String SAMPLE_REQUESTS = "production-sample-requests";
    /** {@code PurchaseOrderResources.GOODS_RECEIPTS}. */
    static final String GOODS_RECEIPTS = "procurement-goods-receipts";
    /** {@code CustomerPriceController.RESOURCE}. */
    static final String CUSTOMER_PRICES = "catalog-customer-prices";
    /** {@code CustomerResources.CREDIT_PROFILES}: a customer's commercial terms. */
    static final String CREDIT_PROFILES = "customer-credit-profiles";
    /** {@code PaymentResources.RECEIVABLES}. */
    static final String RECEIVABLES = "payment-receivables";
    /**
     * {@code CustomerResources.CUSTOMERS}: read here only to decide whether a caller may be shown which customers a
     * name matches, as the customer list does ({@link CustomerMatch}).
     */
    static final String CUSTOMERS = "customer-customers";
    /** The assistant's own resource, for a tool that mirrors no guarded screen: rule search, catalog search. */
    static final String ASSISTANT = "assistant-conversations";

    private ToolPermissions() {
    }
}
