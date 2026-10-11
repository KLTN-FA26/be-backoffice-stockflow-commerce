package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Goods counted in at a receiving location against a goods-receipt line (docs 03 step 6). The
 * stock arrives {@code INBOUND}: physically here, never sellable until it is put away.
 *
 * @param receiptLineId the goods-receipt line, which is also the idempotency key: a retried confirm
 *                      returns the ledger line already written instead of counting the goods twice
 * @param lotNumber     null for a SKU that is not lot-tracked
 * @param actorId       who confirmed the receipt, for the ledger
 * @param receivedAt    when the receipt was confirmed: the new stock layer's receipt time (one layer
 *                      per receipt), which FIFO and the shelf-life policy read
 */
public record ReceiveStockCommand(
        UUID receiptLineId,
        Sku sku,
        String lotNumber,
        LocalDate expiryDate,
        String locationCode,
        int quantity,
        UUID actorId,
        Instant receivedAt
) {

    public ReceiveStockCommand {
        Objects.requireNonNull(receiptLineId, "receiptLineId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (quantity <= 0) {
            throw new IllegalArgumentException("A receipt needs a positive quantity");
        }
    }
}
