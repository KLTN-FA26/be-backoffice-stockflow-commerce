package com.stockflow.inventory.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of the stock history. {@code fromLocation} is null for stock that arrived, {@code toLocation}
 * for stock that left. {@code toStatus} differs from {@code status} only where the line changed it: a putaway
 * (INBOUND to AVAILABLE), a return's decision, or a QC decision from before receiving QC was removed (INBOUND to
 * QUARANTINE or BLOCKED). {@code lpn} names the carton the line is about, so a carton's history can be read line by
 * line (kltn-docs 20 §4.1). One LPN, where there were {@code fromLpn} and {@code toLpn} (V20261230000200, ADR-0011):
 * a line is one whole carton, and which way it went is {@code type} with {@code fromLocation} / {@code toLocation}.
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
                                    UUID countedBy,
                                    @io.swagger.v3.oas.annotations.media.Schema(description = "Display name of "
                                            + "actorId, so the ledger need not read the user list; null for a "
                                            + "system line or an unknown user", nullable = true)
                                    String actorName,
                                    @io.swagger.v3.oas.annotations.media.Schema(description = "Display name of "
                                            + "requestedBy; null whenever requestedBy is", nullable = true)
                                    String requestedByName,
                                    @io.swagger.v3.oas.annotations.media.Schema(description = "Display name of "
                                            + "countedBy; null whenever countedBy is", nullable = true)
                                    String countedByName,
                                    @io.swagger.v3.oas.annotations.media.Schema(description = "The carton (LPN) the "
                                            + "line is about; null only on a line from before stock was only "
                                            + "cartons", nullable = true)
                                    String lpn) {
}
