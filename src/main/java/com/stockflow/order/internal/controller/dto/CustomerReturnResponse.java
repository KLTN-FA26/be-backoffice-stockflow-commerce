package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One of the signed-in customer's own returns, as the portal shows it: what was asked, approved, received, accepted
 * and how it was made good, the deadline to send goods back, why it was refused. Never who in the shop did what, nor
 * their internal notes - the customer reads the decision, not the desk it came from.
 */
@Schema(name = "CustomerReturn")
public record CustomerReturnResponse(
        UUID id,
        @Schema(example = "RMA-20261025-0001") String rmaNumber,
        UUID orderId,
        String orderNumber,
        @Schema(example = "AWAITING_RETURN") String status,
        String reasonCode,
        String description,
        String requestedResolution,
        Instant requestedAt,
        @Schema(description = "Whether the approved goods must be sent back, writing the RMA number on the parcel")
        Boolean returnRequired,
        Instant returnDeadline,
        String rejectionReason,
        Instant receivedAt,
        Instant closedAt,
        String currency,
        BigDecimal refundedAmount,
        BigDecimal creditedAmount,
        BigDecimal offsetAmount,
        UUID replacementOrderId,
        List<Line> lines,
        List<Discrepancy> discrepancies,
        List<Evidence> evidence,
        List<Step> history,
        long version) {

    public record Line(UUID id, UUID orderLineId, String sku, int requestedQuantity, Integer approvedQuantity,
                       Integer receivedQuantity, Integer acceptedQuantity, Integer rejectedQuantity,
                       int replacementQuantity, int creditQuantity, BigDecimal creditAmount) {
    }

    public record Discrepancy(String kind, String sku, int quantity) {
    }

    public record Evidence(UUID id, String stage, String originalName, Instant uploadedAt) {
    }

    public record Step(String status, Instant occurredAt) {
    }
}
