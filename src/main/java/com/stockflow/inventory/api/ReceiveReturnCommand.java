package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Goods a customer sent back under a return request (SCRUM-243 / SCRUM-260, 03 §12), counted in at a quarantine
 * location. They arrive {@code QUARANTINE} (OQ-12): physically here, never sellable until QC decides - put back on sale
 * by a reclassification, or written off by an approved adjustment.
 *
 * @param returnLineId the return line ({@code ordering.return_request_line}), which is also the idempotency key: a
 *                     retried receipt returns the ledger line already written instead of counting the goods twice
 * @param sku          what actually arrived, which may differ from the line's SKU (a wrong item delivered)
 * @param lotNumber    the lot printed on the goods, when the warehouse can read one; null otherwise
 * @param orderId      the order the goods were delivered on: the ledger line names it, so the lot trace reaches it
 * @param receivedAt   when the parcel was counted in: the stock layer's receipt time, which the later decisions on
 *                     these goods name to find them again
 */
public record ReceiveReturnCommand(
        UUID returnLineId,
        Sku sku,
        String lotNumber,
        String locationCode,
        int quantity,
        UUID orderId,
        UUID actorId,
        Instant receivedAt
) {

    public ReceiveReturnCommand {
        Objects.requireNonNull(returnLineId, "returnLineId");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (quantity <= 0) {
            throw new IllegalArgumentException("A return receipt needs a positive quantity");
        }
    }
}
