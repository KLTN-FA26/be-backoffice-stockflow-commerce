package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Move units to another location and give them a new status in the same step, writing one ledger
 * line that records both: QC sending part of a receipt to the quarantine area (INBOUND to QUARANTINE
 * or BLOCKED), a putaway turning INBOUND goods into AVAILABLE ones at the bin.
 *
 * <p>Only INBOUND or QUARANTINE stock can be reclassified: stock that is already sellable, damaged
 * or expired changes status through an adjustment, not through a receiving decision.</p>
 *
 * @param referenceId the document that decided it (a QC inspection, a putaway task); also the
 *                    idempotency key together with {@code reference}
 * @param receivedAt  the stock layer to take from (its receipt time), null when the location holds one
 */
public record ReclassifyStockCommand(
        Sku sku,
        String lotNumber,
        String fromLocation,
        String toLocation,
        int quantity,
        StockDisposition disposition,
        MoveReference reference,
        UUID referenceId,
        UUID actorId,
        String reason,
        Instant receivedAt
) {

    public ReclassifyStockCommand {
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(fromLocation, "fromLocation");
        Objects.requireNonNull(toLocation, "toLocation");
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(referenceId, "referenceId");
        if (quantity <= 0) {
            throw new IllegalArgumentException("A reclassification needs a positive quantity");
        }
    }
}
