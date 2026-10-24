package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A stock adjustment and where it stands.
 *
 * @param stockPackageId   the carton whose contents it changes; null for loose stock
 * @param cycleCountLineId the cycle count line whose variance it is; null for one a person asked for
 */
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
        Instant withdrawnAt,
        long version,
        UUID stockPackageId,
        UUID cycleCountLineId
) {
}
