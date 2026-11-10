package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Goods counted in at a receiving location against a goods-receipt line (docs 03 step 6). The
 * stock arrives {@code INBOUND}: physically here, never sellable until it is put away.
 *
 * <p>The goods go into the cartons labelled on the line while it was counted (SCRUM-473), each holding its standard
 * count. Since team plan 4C-2 every unit received is in one of them (kltn-docs 03 BR-12): a line whose cartons hold
 * less than {@code quantity} is refused, nothing goes loose.</p>
 *
 * @param receiptLineId the goods-receipt line, which is also the idempotency key: a retried confirm
 *                      returns the ledger line already written instead of counting the goods twice; it also
 *                      names the cartons the goods go into
 * @param quantity      everything the line received, in base units: exactly what the line's cartons hold
 * @param lotNumber     required: every SKU is lot-tracked (kltn-docs 01 BR-17, 03 BR-03; team plan 5-5), and the
 *                      database refuses live stock without a lot ({@code inventory.enforce_sku_policy})
 * @param actorId       who confirmed the receipt, for the ledger
 * @param receivedAt    when the receipt was confirmed: the new stock layer's receipt time (one layer
 *                      per receipt), which FIFO reads. There is no expiry date: a receipt records none (03 BR-06)
 * @param origin        the receipt, purchase order and supplier, for the lot the receipt opens (SCRUM-142); null
 *                      when the caller has none, and the stock then joins an UNTRACED lot
 */
public record ReceiveStockCommand(
        UUID receiptLineId,
        Sku sku,
        String lotNumber,
        String locationCode,
        int quantity,
        UUID actorId,
        Instant receivedAt,
        ReceiptOrigin origin
) {

    public ReceiveStockCommand(UUID receiptLineId, Sku sku, String lotNumber, String locationCode, int quantity,
                               UUID actorId, Instant receivedAt) {
        this(receiptLineId, sku, lotNumber, locationCode, quantity, actorId, receivedAt, null);
    }

    public ReceiveStockCommand {
        Objects.requireNonNull(receiptLineId, "receiptLineId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (lotNumber == null || lotNumber.isBlank()) {
            throw new IllegalArgumentException("Every SKU is lot-tracked: a receipt names its lot");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("A receipt needs a positive quantity");
        }
    }
}
