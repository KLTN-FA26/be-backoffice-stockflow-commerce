package com.stockflow.procurement.internal.controller;

/**
 * Permission resource codes owned by the procurement module: goods receipts only, since purchasing went (4A-8). The
 * code keeps its {@code procurement-} prefix so no grant has to move (QD-15).
 */
public final class GoodsReceiptResources {

    public static final String GOODS_RECEIPTS = "procurement-goods-receipts";

    /**
     * WF-05 decision Q-18 (BE-20, BE-43): confirming a return receipt that holds an opened carton or loose units - goods
     * that do not go into stock, counted and destroyed on the receipt - is the warehouse manager's, and not the person's
     * who counted it. APPROVE (V20261216000050).
     */
    public static final String RETURN_RECEIPT_APPROVALS = "procurement-return-receipt-approvals";

    private GoodsReceiptResources() {
    }
}
