package com.stockflow.inventory.internal.domain;

import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.util.UUID;

/**
 * Port: the lots received goods belong to ({@code inventory.stock_lot}, SCRUM-142). A lot is one goods receipt's
 * units of one SKU and lot number — so a lot number reused by another receipt is another lot. Goods received against a
 * production order are the exception (4A-5, kltn-docs 03 BR-03): their lot is the production order's, one per
 * production order and SKU across all its receipts, numbered with its code.
 */
public interface StockLots {

    /**
     * The lot a receipt opens for these goods: found when another line of the same receipt opened it already, made
     * otherwise. The caller holds the SKU's advisory lock, which is what keeps two lines of one receipt from making
     * it twice; {@code uk_stock_lot_receipt} is the last guard. Against a production order, the lot is found when an
     * earlier receipt of it opened one for the SKU ({@code uk_stock_lot_production_order}).
     */
    UUID openForReceipt(ReceiptLot lot);

    /**
     * What a receipt knows about the goods it brings in, snapshotted onto the lot. {@code productionOrderId} and
     * {@code productionOrderCode} are set for goods received against a production order (4A-5), null otherwise.
     */
    record ReceiptLot(Sku sku, String lotNumber, Instant receivedAt, UUID goodsReceiptId,
                      String goodsReceiptNumber, UUID purchaseOrderId, String purchaseOrderNumber, UUID supplierId,
                      String supplierName, UUID warehouseId, UUID productionOrderId, String productionOrderCode) {
    }
}
