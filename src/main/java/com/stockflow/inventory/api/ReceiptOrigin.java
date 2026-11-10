package com.stockflow.inventory.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Where received goods came from, as the receiving documents read at the moment of receipt (SCRUM-142): the lot
 * the receipt opens keeps this snapshot, so the lot trace (SCRUM-153) can name the receipt, the purchase order and
 * the supplier without reading another module's tables, and still names them as they were if a supplier is renamed.
 *
 * <p>4A-5: goods received against a production order name it instead of a purchase order and a supplier
 * ({@code productionOrderId}, {@code productionOrderCode}). Their lot is the production order's - one per production
 * order and SKU, whatever the number of receipts (kltn-docs 03 BR-03) - and its number is the code.</p>
 *
 * <p>Since purchasing went (4A-8) goods are received against production orders only ({@link #ofProductionOrder}); the
 * purchase order and supplier components stay for the shape the lots of earlier receipts keep, and are null on every
 * new origin.</p>
 */
public record ReceiptOrigin(UUID goodsReceiptId, String goodsReceiptNumber, UUID purchaseOrderId,
                            String purchaseOrderNumber, UUID supplierId, String supplierName, UUID warehouseId,
                            UUID productionOrderId, String productionOrderCode) {

    public ReceiptOrigin {
        Objects.requireNonNull(goodsReceiptId, "goodsReceiptId");
        if ((productionOrderId == null) != (productionOrderCode == null)) {
            throw new IllegalArgumentException("A production order is named by its id and its code");
        }
        if (productionOrderId != null && (purchaseOrderId != null || supplierId != null)) {
            throw new IllegalArgumentException("Goods come from a production order or from a purchase, not both");
        }
    }

    /** Goods received against a production order (4A-5). */
    public static ReceiptOrigin ofProductionOrder(UUID goodsReceiptId, String goodsReceiptNumber, UUID warehouseId,
                                                  UUID productionOrderId, String productionOrderCode) {
        return new ReceiptOrigin(goodsReceiptId, goodsReceiptNumber, null, null, null, null, warehouseId,
                Objects.requireNonNull(productionOrderId, "productionOrderId"),
                Objects.requireNonNull(productionOrderCode, "productionOrderCode"));
    }
}
