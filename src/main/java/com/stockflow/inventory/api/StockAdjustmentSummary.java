package com.stockflow.inventory.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A stock adjustment and where it stands.
 *
 * @param direction        whether the carton is written off whole or restocked whole; never a number of units
 * @param stockPackageId   the carton it writes off or restocks
 * @param cycleCountLineId the cycle count line whose variance it is; null for one a person asked for
 * @param countedBy        who counted that line: neither they nor the requester may decide it; null when nobody did
 */
public record StockAdjustmentSummary(
        UUID adjustmentId,
        String number,
        String locationCode,
        String sku,
        String lotNumber,
        StockAdjustmentDirection direction,
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
        UUID cycleCountLineId,
        UUID countedBy
) {

    /** An adjustment nobody counted: asked by hand. */
    public StockAdjustmentSummary(UUID adjustmentId, String number, String locationCode, String sku, String lotNumber,
                                  StockAdjustmentDirection direction, StockAdjustmentReason reason, String note,
                                  StockAdjustmentStatus status, UUID requestedBy, Instant requestedAt, UUID decidedBy,
                                  Instant decidedAt, String rejectionReason, Instant postedAt, Instant withdrawnAt,
                                  long version, UUID stockPackageId, UUID cycleCountLineId) {
        this(adjustmentId, number, locationCode, sku, lotNumber, direction, reason, note, status, requestedBy,
                requestedAt, decidedBy, decidedAt, rejectionReason, postedAt, withdrawnAt, version, stockPackageId,
                cycleCountLineId, null);
    }
}
