package com.stockflow.inventory.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of the stock history. {@code fromLocation} is null for stock that arrived, {@code toLocation}
 * for stock that left. {@code toStatus} differs from {@code status} only where the line changed it: a putaway
 * (INBOUND to AVAILABLE), a return's decision, or a QC decision from before receiving QC was removed (INBOUND to
 * QUARANTINE or BLOCKED).
 */
public record StockMovementResponse(UUID movementId, String type, String sku, String lotNumber,
                                    String fromLocation, String toLocation, int quantity, String status,
                                    String toStatus,
                                    String referenceType, UUID referenceId, String reason, UUID actorId,
                                    Instant occurredAt,
                                    @io.swagger.v3.oas.annotations.media.Schema(description = "On an ADJUSTMENT line: "
                                            + "who asked for the adjustment; actorId is who approved it. Null on "
                                            + "every other line", nullable = true)
                                    UUID requestedBy,
                                    @io.swagger.v3.oas.annotations.media.Schema(description = "On an ADJUSTMENT line "
                                            + "of a cycle count's variance: who counted the line", nullable = true)
                                    UUID countedBy) {
}
