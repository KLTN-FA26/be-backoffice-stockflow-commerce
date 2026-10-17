package com.stockflow.inventory.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Where received goods came from, as the receiving documents read at the moment of receipt (SCRUM-142): the lot
 * the receipt opens keeps this snapshot, so the lot trace (SCRUM-153) can name the receipt, the purchase order and
 * the supplier without reading another module's tables, and still names them as they were if a supplier is renamed.
 */
public record ReceiptOrigin(UUID goodsReceiptId, String goodsReceiptNumber, UUID purchaseOrderId,
                            String purchaseOrderNumber, UUID supplierId, String supplierName, UUID warehouseId) {

    public ReceiptOrigin {
        Objects.requireNonNull(goodsReceiptId, "goodsReceiptId");
    }
}
