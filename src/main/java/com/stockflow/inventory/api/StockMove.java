package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A move that happened: the ledger line it wrote. A line is one whole carton and carries no quantity (ADR-0011); the
 * units are the carton's standard count.
 */
public record StockMove(
        UUID movementId,
        String sku,
        String lotNumber,
        String fromLocation,
        String toLocation,
        Instant occurredAt
) {
}
