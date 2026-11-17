package com.stockflow.warehouse.api;

import java.util.Objects;
import java.util.UUID;

/**
 * What the warehouse is asked to put away: one carton (kltn-docs 05 BR-08; since team plan 4C-2 there is no loose
 * putaway task), either of a counted goods receipt line or released from quarantine (05 BR-01; team plan 4D-1).
 *
 * @param goodsReceiptId     the receipt of a {@link PutawaySource#RECEIPT} task; null for a quarantine release
 * @param goodsReceiptLineId its line; null for a quarantine release
 * @param quantity           base units: the units that carton holds
 * @param stockPackageId     the carton; required
 */
public record NewPutawayTask(UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, int quantity,
                             UUID stockPackageId, PutawaySource source) {

    public NewPutawayTask {
        Objects.requireNonNull(source, "source");
    }

    /** A carton of a goods receipt line. */
    public NewPutawayTask(UUID goodsReceiptId, UUID goodsReceiptLineId, String sku, int quantity,
                          UUID stockPackageId) {
        this(goodsReceiptId, goodsReceiptLineId, sku, quantity, stockPackageId, PutawaySource.RECEIPT);
    }

    /** A carton a warehouse manager released from quarantine, to go back into a bin. */
    public static NewPutawayTask quarantineRelease(String sku, int quantity, UUID stockPackageId) {
        return new NewPutawayTask(null, null, sku, quantity, stockPackageId, PutawaySource.QUARANTINE_RELEASE);
    }
}
