package com.stockflow.assistant.internal.service;

/**
 * The permission resources the tools carry: the same codes the mirrored screens' controllers declare.
 *
 * <p>Copied by value, because those constants are internal to their modules. {@code AssistantIntegrationTest.everyToolMirrorsItsScreen}
 * compares each tool's annotation with the endpoint it mirrors, so a renamed resource fails the build instead of
 * leaving a tool guarded by a permission nobody holds.</p>
 */
final class ToolPermissions {

    /** {@code InventoryResources.STOCK_ITEMS}. */
    static final String INVENTORY_STOCK_ITEMS = "inventory-stock-items";
    /** {@code InventoryResources.STOCK_PACKAGES}: the carton lookup ({@link LookupScanTool}). */
    static final String STOCK_PACKAGES = "inventory-stock-packages";
    /** {@code InventoryResources.INVENTORY_ITEMS}: the item list, read for a product barcode. */
    static final String INVENTORY_ITEMS = "inventory-inventory-items";
    /** {@code InventoryResources.STOCK_MOVEMENTS}. */
    static final String INVENTORY_STOCK_MOVEMENTS = "inventory-stock-movements";
    /**
     * {@code InventoryResources.STOCK_ADJUSTMENTS}: READ for the approval queue; APPROVE read here only to say whether
     * the caller may approve ({@link FindPendingAdjustmentsTool}).
     */
    static final String STOCK_ADJUSTMENTS = "inventory-stock-adjustments";
    /** {@code OrderResources.ORDERS}. */
    static final String SALES_ORDERS = "sales-orders";
    /**
     * {@code OrderResources.PRODUCTION_ORDERS}: the production orders screen ({@link FindProductionOrdersTool}); read
     * by findOrder too, to say which production orders an order still waits for.
     */
    static final String PRODUCTION_ORDERS = "production-orders";
    /**
     * {@code OrderResources.SHIPPING_WAREHOUSES}: read here only to decide whether findOrder may say how much of an
     * order is held at its shipping warehouse, as the order's stock coverage screen does.
     */
    static final String SHIPPING_WAREHOUSES = "sales-order-shipping-warehouses";
    /**
     * {@code PaymentResources.INSTALLMENTS}: read here only to decide whether findOrder may show an order's contract
     * instalments, as the order's instalment screen ({@code GET /api/v1/installments/orders/{orderId}}) does.
     */
    static final String INSTALLMENTS = "payment-installments";
    /** {@code InvoicingResources.EINVOICES}: the accountant's e-invoice list ({@link FindEInvoicesTool}). */
    static final String EINVOICES = "einvoices";
    /** {@code ReportingResources.REPORTS}. */
    static final String REPORTS = "reporting-reports";
    /** {@code FulfillmentResources.PICK_LISTS}. */
    static final String PICK_LISTS = "fulfillment-pick-lists";
    /** {@code FulfillmentResources.SHIPMENTS}. */
    static final String SHIPMENTS = "fulfillment-shipments";
    /**
     * {@code FulfillmentPermissions.TASK_OVERSIGHT}: read here only to say which of fulfillment's task-visibility rules
     * applied to a caller ({@link FindFulfillmentTasksTool}).
     */
    static final String TASK_OVERSIGHT = "fulfillment-task-oversight";
    /** {@code GoodsReceiptResources.GOODS_RECEIPTS}. */
    static final String GOODS_RECEIPTS = "procurement-goods-receipts";
    /** {@code CustomerPriceController.RESOURCE}. */
    static final String CUSTOMER_PRICES = "catalog-customer-prices";
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
