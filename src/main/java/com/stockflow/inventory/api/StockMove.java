package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.UUID;

/** A move that happened: the ledger line it wrote. */
public record StockMove(
        UUID movementId,
        String sku,
        String lotNumber,
        String fromLocation,
        String toLocation,
        int quantity,
        Instant occurredAt
) {
}
