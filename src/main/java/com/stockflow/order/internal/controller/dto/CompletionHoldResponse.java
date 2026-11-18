package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * A pause of an order's automatic completion (WF-05 GH-2): open while {@code releasedAt} is null. The order keeps its
 * status and its other batches move on; only the nightly completion waits.
 */
@Schema(description = "A pause of an order's automatic completion")
public record CompletionHoldResponse(
        UUID id,
        UUID orderId,
        @Schema(description = "DELIVERY_DISPUTED or OTHER") String reasonCode,
        String note,
        Instant heldAt,
        UUID heldBy,
        Instant releasedAt,
        UUID releasedBy,
        String releaseNote
) {
}
