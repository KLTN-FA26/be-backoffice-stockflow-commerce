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
        List<Line> lines,
        java.time.Instant confirmedAt
) {

    /**
     * One counted line. {@code qcRequired}: the line goes through the QC area before putaway. {@code locationCode}: where the line's stock was counted in; with {@code confirmedAt} (its receipt time) it
     * names the stock layer the line created — production holds a subcontractor's goods on it (SCRUM-441).
     */
    public record Line(UUID receiptLineId, UUID purchaseOrderLineId, String sku, String lotNumber, int quantity,
                       boolean qcRequired, String locationCode) {

        public Line(UUID receiptLineId, UUID purchaseOrderLineId, String sku, String lotNumber, int quantity,
                    boolean qcRequired) {
            this(receiptLineId, purchaseOrderLineId, sku, lotNumber, quantity, qcRequired, null);
        }
    }

    public GoodsReceiptConfirmed(UUID receiptId, String receiptNumber, UUID purchaseOrderId, UUID warehouseId,
                                 List<Line> lines) {
        this(receiptId, receiptNumber, purchaseOrderId, warehouseId, lines, null);
    }

    public GoodsReceiptConfirmed {
        lines = List.copyOf(lines);
    }
}
