package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.UUID;

/** A stock adjustment and where it stands. */
public record StockAdjustmentSummary(
        UUID adjustmentId,
        String number,
        String locationCode,
        String sku,
        String lotNumber,
        int quantityDelta,
        StockAdjustmentReason reason,
        String note,
        StockAdjustmentStatus status,
        UUID requestedBy,
        Instant requestedAt,
        UUID decidedBy,
        Instant decidedAt,
        String rejectionReason,
        Instant postedAt,
        long version
) {
}
