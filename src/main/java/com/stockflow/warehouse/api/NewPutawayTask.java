package com.stockflow.warehouse.api;

import java.util.UUID;

/**
 * What the receiving flow asks the warehouse to put away: one counted goods receipt line, or one carton of it.
 *
 * @param quantity       base units; for a carton the units that carton holds
 * @param stockPackageId the carton, or {@code null} for loose stock
 */
public record NewPutawayTask(UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, int quantity,
                             UUID stockPackageId) {
}
