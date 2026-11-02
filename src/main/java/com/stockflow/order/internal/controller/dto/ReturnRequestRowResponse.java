package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One return in a list: the back-office queue, or a customer's own (where {@code customerId} is theirs). */
@Schema(name = "ReturnRequestRow")
public record ReturnRequestRowResponse(UUID id, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                                       @Schema(example = "REQUESTED") String status, String reasonCode,
                                       String requestedResolution, String channel, Instant requestedAt,
                                       Instant returnDeadline, BigDecimal settlementAmount, BigDecimal refundedAmount,
                                       BigDecimal creditedAmount, BigDecimal offsetAmount, String currency,
                                       boolean vatAdjustmentRequired, UUID replacementOrderId, Instant closedAt,
                                       long version) {
}
