package com.stockflow.procurement.internal.controller;

/**
 * Permission resource codes owned by the procurement module.
 */
public final class PurchaseOrderResources {

    public static final String PURCHASE_ORDERS = "procurement-purchase-orders";
    public static final String GOODS_RECEIPTS = "procurement-goods-receipts";
    /** QC decisions on received goods; seeded with VIEW_PAGE, READ and APPROVE (QC_STAFF holds APPROVE). */
    public static final String QC_TASKS = "procurement-qc-tasks";

    private PurchaseOrderResources() {
    }
}
