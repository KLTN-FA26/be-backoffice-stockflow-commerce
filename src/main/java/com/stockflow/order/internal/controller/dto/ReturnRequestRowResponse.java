package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One return in a list: the back-office queue, or a customer's own (where {@code customerId} is theirs).
 * {@code shipmentId}: the delivered shipment it is against (WF-05 TH-1), so a list of an order delivered in several can
 * tell its returns apart; null only for an order delivered without shipments.
 */
@Schema(name = "ReturnRequestRow")
public record ReturnRequestRowResponse(UUID id, String rmaNumber, UUID orderId, String orderNumber, UUID customerId,
                                       @Schema(example = "REQUESTED") String status, String reasonCode,
                                       String requestedResolution, String channel, Instant requestedAt,
                                       Instant returnDeadline, BigDecimal settlementAmount, BigDecimal refundedAmount,
                                       BigDecimal offsetAmount, String currency, boolean vatAdjustmentRequired,
                                       Instant closedAt, long version, UUID shipmentId) {
}
