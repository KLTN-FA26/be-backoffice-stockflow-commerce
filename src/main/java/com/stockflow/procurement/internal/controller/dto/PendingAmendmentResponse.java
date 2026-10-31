package com.stockflow.procurement.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(name = "PendingAmendment", description = "The amendment waiting for a decision on an approved purchase order")
public record PendingAmendmentResponse(
        long revisionNo,
        UUID requestedBy,
        String requestedByName,
        Instant requestedAt,
        String reason,
        @Schema(description = "What the order totals, after tax, once the amendment is approved")
                BigDecimal newTotalAmount) {}
