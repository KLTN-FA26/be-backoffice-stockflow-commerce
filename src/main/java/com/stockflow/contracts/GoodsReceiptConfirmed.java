package com.stockflow.contracts;

import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>procurement</b> module when a goods receipt's count is final (docs 03 step 6):
 * the goods are in stock as INBOUND at the receiving location, and the purchase order's progress has
 * been updated. For invoice matching (docs 04) and production subcontracting, which receives its
 * finished goods through a receipt.
 *
 * <p>Keyed by {@code receiptId}: a consumer that sees it twice must not count the receipt twice.
 * Consumers receive it in-process through {@code @ApplicationModuleListener}; see
 * {@code package-info.java} for the rules on changing this contract.</p>
 */
public record GoodsReceiptConfirmed(
        UUID receiptId,
        String receiptNumber,
        UUID purchaseOrderId,
        UUID warehouseId,
        List<Line> lines
) {

    /** One counted line. {@code qcRequired}: the line goes through the QC area before putaway. */
    public record Line(UUID receiptLineId, UUID purchaseOrderLineId, String sku, String lotNumber, int quantity,
                       boolean qcRequired) {
    }

    public GoodsReceiptConfirmed {
        lines = List.copyOf(lines);
    }
}
