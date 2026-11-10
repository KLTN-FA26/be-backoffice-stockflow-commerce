package com.stockflow.warehouse.api;

import java.util.UUID;

/**
 * What the receiving flow asks the warehouse to put away: one carton of a counted goods receipt line (kltn-docs 05
 * BR-08; since team plan 4C-2 there is no loose putaway task).
 *
 * @param quantity       base units: the units that carton holds
 * @param stockPackageId the carton; required
 */
public record NewPutawayTask(UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, int quantity,
                             UUID stockPackageId) {
}
