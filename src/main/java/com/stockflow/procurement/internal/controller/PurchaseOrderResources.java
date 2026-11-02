package com.stockflow.procurement.internal.controller;

/**
 * Permission resource codes owned by the procurement module.
 */
public final class PurchaseOrderResources {

    public static final String PURCHASE_ORDERS = "procurement-purchase-orders";
    public static final String GOODS_RECEIPTS = "procurement-goods-receipts";
    /** QC decisions on received goods; seeded with VIEW_PAGE, READ and APPROVE (QC_STAFF holds APPROVE). */
    public static final String QC_TASKS = "procurement-qc-tasks";
    /**
     * Supplier invoices and their three-way match (SCRUM-308/309); seeded with VIEW_PAGE, READ, CREATE, UPDATE for the
     * accountant and procurement staff, APPROVE for the accountant (resolving variances, SCRUM-310).
     */
    public static final String SUPPLIER_INVOICES = "procurement-supplier-invoices";

    private PurchaseOrderResources() {
    }
}
