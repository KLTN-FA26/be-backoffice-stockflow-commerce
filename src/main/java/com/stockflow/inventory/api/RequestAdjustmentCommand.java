package com.stockflow.inventory.api;

import com.stockflow.common.domain.Sku;

import java.util.Objects;
import java.util.UUID;

/**
 * Ask for the quantity of one stock item to be corrected by {@code quantityDelta} (SCRUM-145).
 * Nothing changes until someone else approves it.
 *
 * @param quantityDelta negative to write off, positive to write up; never zero
 * @param note          required when the reason is {@link StockAdjustmentReason#OTHER}
 */
public record RequestAdjustmentCommand(
        String locationCode,
        Sku sku,
        String lotNumber,
        int quantityDelta,
        StockAdjustmentReason reason,
        String note,
        UUID requestedBy
) {

    public RequestAdjustmentCommand {
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(sku, "sku");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(requestedBy, "requestedBy");
    }
}
