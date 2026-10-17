package com.stockflow.production.internal.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductionOrderResponse(ProductionOrderRowResponse summary, UUID approvedSampleId, String designChecksum,
                                      int issuedQuantity, int printedQuantity, int scrapQuantity,
                                      Instant prepressCheckedAt, Instant completedAt, Instant cancelledAt,
                                      String cancellationReason, String createdBy, Instant lastModifiedAt,
                                      long version, List<StatusChange> history, String origin,
                                      Subcontract subcontract) {

    public record StatusChange(String fromStatus, String toStatus, String reason, Instant changedAt, UUID changedBy) {
    }

    /** SUPPLIED_BLANKS or FULL_SERVICE, who prints, at what price per good unit, on which SUBCONTRACT PO. */
    public record Subcontract(String method, UUID subcontractorId, java.math.BigDecimal unitPrice, String currency,
                              UUID purchaseOrderId) {
    }
}
