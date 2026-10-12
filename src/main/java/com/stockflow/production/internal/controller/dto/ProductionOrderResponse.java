package com.stockflow.production.internal.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductionOrderResponse(ProductionOrderRowResponse summary, UUID approvedSampleId, String designChecksum,
                                      int issuedQuantity, int printedQuantity, int scrapQuantity,
                                      Instant prepressCheckedAt, Instant completedAt, Instant cancelledAt,
                                      String cancellationReason, String createdBy, Instant lastModifiedAt,
                                      long version, List<StatusChange> history) {

    public record StatusChange(String fromStatus, String toStatus, String reason, Instant changedAt, UUID changedBy) {
    }
}
