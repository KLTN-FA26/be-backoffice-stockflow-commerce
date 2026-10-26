package com.stockflow.inventory.api;

import java.util.UUID;

/**
 * One carton (stock package) labelled on a goods-receipt line, as the receipt shows it (SCRUM-473).
 *
 * @param uomConversionId the packing unit it was made in; {@code nominalQty} is that unit's factor when it was made
 * @param quantity        what was counted into it while it is RECEIVING (null until counted); once in stock, what is
 *                        left in it - the sum of its stock rows, since a carton holds no quantity of its own
 * @param status          RECEIVING, IN_STOCK, CONSUMED or LOST ({@code inventory.stock_package.status})
 * @param unopened        still closed as it was counted: then it holds exactly what was counted into it
 */
public record StockPackageView(
        UUID id,
        String lpn,
        UUID receiptLineId,
        String sku,
        UUID uomConversionId,
        String unitOfMeasure,
        int nominalQty,
        Integer quantity,
        String locationCode,
        String status,
        boolean unopened
) {
}
