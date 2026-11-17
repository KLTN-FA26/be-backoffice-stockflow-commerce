package com.stockflow.inventory.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Where received goods came from, as the receiving documents read at the moment of receipt (SCRUM-142): the lot
 * the receipt opens keeps this snapshot, so the lot trace (SCRUM-153) can name the receipt and the production order
 * without reading another module's tables.
 *
 * <p>4A-5: goods received against a production order name it ({@code productionOrderId}, {@code productionOrderCode}).
 * Their lot is the production order's - one per production order and SKU, whatever the number of receipts (kltn-docs
 * 03 BR-03) - and its number is the code. Purchasing went in 4A-8 and its purchase order and supplier components with
 * V20261117000200 (review Q-2): goods are received against production orders only ({@link #ofProductionOrder}).</p>
 */
public record ReceiptOrigin(UUID goodsReceiptId, String goodsReceiptNumber, UUID warehouseId,
                            UUID productionOrderId, String productionOrderCode) {

    public ReceiptOrigin {
        Objects.requireNonNull(goodsReceiptId, "goodsReceiptId");
        if ((productionOrderId == null) != (productionOrderCode == null)) {
            throw new IllegalArgumentException("A production order is named by its id and its code");
        }
    }

    /** Goods received against a production order (4A-5). */
    public static ReceiptOrigin ofProductionOrder(UUID goodsReceiptId, String goodsReceiptNumber, UUID warehouseId,
                                                  UUID productionOrderId, String productionOrderCode) {
        return new ReceiptOrigin(goodsReceiptId, goodsReceiptNumber, warehouseId,
                Objects.requireNonNull(productionOrderId, "productionOrderId"),
                Objects.requireNonNull(productionOrderCode, "productionOrderCode"));
    }
}
