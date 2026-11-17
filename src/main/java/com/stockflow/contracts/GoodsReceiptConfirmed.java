package com.stockflow.contracts;

import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>procurement</b> module when a goods receipt's count is final (docs 03 step 6):
 * the goods are in stock as INBOUND at the receiving location, and the production order's progress has
 * been updated. No module listens to it today; it is the fact a receipt's consumers start from.
 *
 * <p>Since purchasing went (4A-8) every receipt confirmed is against a production order (4A-5): the purchase order
 * and its lines this record named gave way to {@code productionOrderId} and each line's
 * {@code productionOrderLineId}. Nothing listened to it, so no stored publication carries the old shape.</p>
 *
 * <p>Keyed by {@code receiptId}: a consumer that sees it twice must not count the receipt twice.
 * Consumers receive it in-process through {@code @ApplicationModuleListener}; see
 * {@code package-info.java} for the rules on changing this contract.</p>
 */
public record GoodsReceiptConfirmed(
        UUID receiptId,
        String receiptNumber,
        UUID productionOrderId,
        UUID warehouseId,
        List<Line> lines,
        java.time.Instant confirmedAt
) {

    /**
     * One counted line. {@code locationCode}: where the line's stock was counted in; with {@code confirmedAt} (its
     * receipt time) it names the stock layer the line created.
     */
    public record Line(UUID receiptLineId, UUID productionOrderLineId, String sku, String lotNumber, int quantity,
                       String locationCode) {
    }

    public GoodsReceiptConfirmed {
        lines = List.copyOf(lines);
    }
}
