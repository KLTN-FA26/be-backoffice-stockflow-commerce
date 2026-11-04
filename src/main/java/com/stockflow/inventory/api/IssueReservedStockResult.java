package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Where a hold went. {@code moved} is false when the hold was already at {@code toLocation} — a retry,
 * answered from the stock as it stands; {@code movementId} is then null, as nothing was written.
 */
public record IssueReservedStockResult(UUID reservationId, UUID stockItemId, String sku, String lotNumber,
                                       String fromLocation, String toLocation, int quantity, boolean moved,
                                       UUID movementId, Instant occurredAt) {
}
