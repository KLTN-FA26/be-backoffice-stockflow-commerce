package com.stockflow.inventory.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

public record StockAdjustmentResponse(UUID adjustmentId, String number, String locationCode, String sku,
                                      String lotNumber, int quantityDelta, String reason, String note,
                                      String status, UUID requestedBy, Instant requestedAt, UUID decidedBy,
                                      Instant decidedAt, String rejectionReason, Instant postedAt, long version) {
}
