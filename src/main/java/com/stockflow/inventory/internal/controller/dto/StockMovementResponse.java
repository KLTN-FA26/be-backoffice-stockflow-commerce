package com.stockflow.inventory.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of the stock history. {@code fromLocation} is null for stock that arrived, {@code toLocation}
 * for stock that left. {@code toStatus} differs from {@code status} only where the line changed it: a QC
 * decision (INBOUND to QUARANTINE or BLOCKED), a putaway (INBOUND to AVAILABLE).
 */
public record StockMovementResponse(UUID movementId, String type, String sku, String lotNumber,
                                    String fromLocation, String toLocation, int quantity, String status,
                                    String toStatus,
                                    String referenceType, UUID referenceId, String reason, UUID actorId,
                                    Instant occurredAt) {
}
