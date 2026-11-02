package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "PurchaseOrderRevision", description = "One revision of a purchase order and how it was decided")
public record PurchaseOrderRevisionResponse(
        long revisionNo,
        @Schema(description = "SUBMISSION when the revision froze a draft submitted for approval, AMENDMENT when it"
                + " changes an approved order") String purpose,
        @Schema(description = "ACTIVE, PENDING, REJECTED, WITHDRAWN or SUPERSEDED") String state,
        UUID changedBy,
        String changedByName,
        Instant changedAt,
        String reason,
        BigDecimal totalAmount,
        @Schema(description = "The last decision: APPROVED, AUTO_APPROVED, REJECTED or WITHDRAWN; null while waiting")
                String decision,
        UUID decidedBy,
        String decidedByName,
        Instant decidedAt,
        String decisionReason,
        @Schema(description = "The approval limit an AUTO_APPROVED decision was within; null otherwise")
                BigDecimal autoApprovalLimit) {}
