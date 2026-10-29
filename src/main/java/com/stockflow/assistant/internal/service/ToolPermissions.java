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
    /** {@code OrderResources.ORDERS}. */
    static final String SALES_ORDERS = "sales-orders";
    /** {@code PurchaseOrderResources.PURCHASE_ORDERS}. */
    static final String PURCHASE_ORDERS = "procurement-purchase-orders";
    /** {@code ReportingResources.REPORTS}. */
    static final String REPORTS = "reporting-reports";
    /** {@code FulfillmentResources.PICK_LISTS}. */
    static final String PICK_LISTS = "fulfillment-pick-lists";
    /** {@code SupplierResources.SUPPLIER_ITEMS}. */
    static final String SUPPLIER_ITEMS = "procurement-supplier-items";
    /** {@code ProductionResources.PRODUCTION_ORDERS}. */
    static final String PRODUCTION_ORDERS = "production-orders";
    /** {@code ProductionResources.SAMPLE_REQUESTS}: read here only to decide what a production order may name. */
    static final String SAMPLE_REQUESTS = "production-sample-requests";
    /** The assistant's own resource, for a tool that mirrors no guarded screen: rule search, catalog search. */
    static final String ASSISTANT = "assistant-conversations";

    private ToolPermissions() {
    }
}
