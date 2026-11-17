package com.stockflow.procurement.internal.controller;

/**
 * Permission resource codes owned by the procurement module: goods receipts only, since purchasing went (4A-8). The
 * code keeps its {@code procurement-} prefix so no grant has to move (QD-15).
 */
public final class GoodsReceiptResources {

    public static final String GOODS_RECEIPTS = "procurement-goods-receipts";

    private GoodsReceiptResources() {
    }
}
