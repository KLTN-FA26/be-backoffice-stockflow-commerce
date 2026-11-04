package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One customer request to cancel an order (SCRUM-460). */
@Schema(name = "OrderCancellationRequest")
public record CancellationRequestResponse(UUID requestId, UUID orderId, UUID requestedBy,
                                          @Schema(example = "CUSTOMER_REQUEST") String reasonCode, String note,
                                          Instant requestedAt, @Schema(example = "PENDING") String status,
                                          UUID decidedBy, Instant decidedAt, String decisionNote,
                                          BigDecimal retainedPercent, long version) {
}
