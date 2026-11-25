package com.stockflow.inventory.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of the stock history. {@code fromLocation} is null for stock that arrived, {@code toLocation}
 * for stock that left. {@code toStatus} differs from {@code status} only where the line changed it: a putaway
 * (INBOUND to AVAILABLE), a return's decision, or a QC decision from before receiving QC was removed (INBOUND to
 * QUARANTINE or BLOCKED). {@code fromLpn} / {@code toLpn} name the carton on each side, so a carton's history can be
 * read line by line (kltn-docs 20 §4.1); a putaway or a move of a whole carton names the same LPN on both.
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
                                    @io.swagger.v3.oas.annotations.media.Schema(description = "The carton (LPN) the "
                                            + "units left; null for a side with no carton", nullable = true)
                                    String fromLpn,
                                    @io.swagger.v3.oas.annotations.media.Schema(description = "The carton (LPN) the "
                                            + "units went into; null for a side with no carton", nullable = true)
                                    String toLpn) {
}
