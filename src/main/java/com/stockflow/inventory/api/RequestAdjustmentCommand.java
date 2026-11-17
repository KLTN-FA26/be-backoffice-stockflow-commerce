package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.util.Objects;
import java.util.UUID;

/**
 * Ask for the quantity of one stock item to be corrected by {@code quantityDelta} (SCRUM-145), or for one whole carton
 * to be written off by its LPN (kltn-docs 20 BR-14, BR-21; team plan 4D-1). Nothing changes until someone else
 * approves it.
 *
 * @param locationCode  the loose stock's location; with an LPN optional, and then it must be the carton's
 * @param sku           the loose stock's SKU; with an LPN optional, and then it must be the carton's
 * @param quantityDelta negative to write off, positive to write up; never zero. For a carton, minus what it holds
 * @param note          required when the reason is {@link StockAdjustmentReason#OTHER}
 * @param receivedAt    the receipt layer, when the location holds several layers of the lot (one per receipt);
 *                      null for the only one
 * @param lpn           the carton to write off whole; null for loose stock
 */
public record RequestAdjustmentCommand(
        String locationCode,
        Sku sku,
        String lotNumber,
        int quantityDelta,
        StockAdjustmentReason reason,
        String note,
        UUID requestedBy,
        java.time.Instant receivedAt,
        String lpn
) {

    /** Loose stock, at the given receipt layer. */
    public RequestAdjustmentCommand(String locationCode, Sku sku, String lotNumber, int quantityDelta,
                                    StockAdjustmentReason reason, String note, UUID requestedBy,
                                    java.time.Instant receivedAt) {
        this(locationCode, sku, lotNumber, quantityDelta, reason, note, requestedBy, receivedAt, null);
    }

    /** For a location that holds one layer of the lot. */
    public RequestAdjustmentCommand(String locationCode, Sku sku, String lotNumber, int quantityDelta,
                                    StockAdjustmentReason reason, String note, UUID requestedBy) {
        this(locationCode, sku, lotNumber, quantityDelta, reason, note, requestedBy, null, null);
    }

    /** A whole carton, written off by its LPN. */
    public static RequestAdjustmentCommand ofCarton(String lpn, int quantityDelta, StockAdjustmentReason reason,
                                                    String note, UUID requestedBy) {
        return new RequestAdjustmentCommand(null, null, null, quantityDelta, reason, note, requestedBy, null,
                Objects.requireNonNull(lpn, "lpn"));
    }

    public RequestAdjustmentCommand {
        lpn = lpn == null || lpn.isBlank() ? null : lpn;
        if (lpn == null) {
            Objects.requireNonNull(locationCode, "locationCode");
            Objects.requireNonNull(sku, "sku");
        }
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requestedBy, "requestedBy");
    }
}
