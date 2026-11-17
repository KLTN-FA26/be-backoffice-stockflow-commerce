package com.stockflow.contracts;

import java.util.List;
import java.util.UUID;

/**
 * Published by the <b>procurement</b> module when received goods may be put away (docs 03 step 7): at
 * confirmation, for every line - there is no quality check at the warehouse (kltn-docs 03 BR-07). The stock is
 * INBOUND at {@code locationCode}, the receiving area; putaway (SCRUM-92) moves it to a bin and makes it AVAILABLE.
 *
 * <p>Per receipt line, at most once each: keyed by {@code receiptLineId}. Consumers receive it
 * in-process through {@code @ApplicationModuleListener}; see {@code package-info.java} for the rules
 * on changing this contract.</p>
 */
public record ReceiptStockReadyForPutaway(
        UUID receiptId,
        String receiptNumber,
        UUID warehouseId,
        List<Line> lines
) {

    public record Line(UUID receiptLineId, String sku, String lotNumber, String locationCode, int quantity) {
    }

    public ReceiptStockReadyForPutaway {
        lines = List.copyOf(lines);
    }
}
