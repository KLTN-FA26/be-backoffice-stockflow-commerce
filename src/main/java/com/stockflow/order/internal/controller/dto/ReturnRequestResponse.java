package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One return as the back office sees it (SCRUM-243 / SCRUM-260): every step, who took it and why, the photos, what the
 * parcel held, and the status history. Grouped by step so a screen shows each when it has happened.
 */
@Schema(name = "ReturnRequest")
public record ReturnRequestResponse(
        UUID id,
        @Schema(example = "RMA-20261025-0001") String rmaNumber,
        UUID orderId,
        String orderNumber,
        UUID customerId,
        @Schema(example = "AWAITING_RETURN") String status,
        @Schema(example = "PRINT_DEFECT") String reasonCode,
        String description,
        @Schema(example = "REPLACEMENT") String requestedResolution,
        @Schema(example = "PORTAL") String channel,
        UUID requestedBy,
        Instant requestedAt,
        String currency,
        Review review,
        Receipt receipt,
        Inspection inspection,
        Resolution resolution,
        UUID cancelledBy,
        String cancellationReason,
        Instant closedAt,
        List<Line> lines,
        List<Evidence> evidence,
        List<Discrepancy> discrepancies,
        List<HistoryEntry> history,
        long version) {

    /** Sales' decision; {@code returnDeadline}: the goods must arrive by then, or the return expires. */
    public record Review(UUID decidedBy, Instant decidedAt, String note, String rejectionReason, Boolean returnRequired,
                         Instant returnDeadline) {
    }

    public record Receipt(UUID receivedBy, Instant receivedAt, String locationCode, String note) {
    }

    public record Inspection(UUID inspectedBy, Instant inspectedAt, String note) {
    }

    /**
     * How the accepted units are made good. {@code settlementAmount} = credited units x price; booked as
     * {@code offsetAmount} (receivables reduced) + {@code refundedAmount} (PENDING refunds) + {@code creditedAmount}
     * (kept as credit). {@code vatAdjustmentRequired}: accounting issues an adjustment invoice (Nghị định 123/2020).
     */
    public record Resolution(@Schema(example = "REFUND") String moneyMode, BigDecimal settlementAmount, UUID proposedBy,
                             Instant proposedAt, String note, UUID settledBy, Instant settledAt, BigDecimal offsetAmount,
                             BigDecimal refundedAmount, BigDecimal creditedAmount, UUID creditTransferId,
                             UUID replacementOrderId, boolean vatAdjustmentRequired) {
    }

    /**
     * One order line of the return, its units at every step. {@code receivedSku} is set only when another SKU than the
     * line's arrived; {@code scrapAdjustmentId} / {@code customerReturnAdjustmentId} are the inventory write-offs a
     * second person approves.
     */
    public record Line(UUID id, UUID orderLineId, String sku, boolean customPrinted, BigDecimal unitPrice,
                       int requestedQuantity, Integer approvedQuantity, String receivedSku, Integer receivedQuantity,
                       String lotNumber, Integer acceptedQuantity, Integer rejectedQuantity, String condition,
                       String inspectionNote, int restockQuantity, int scrapQuantity, int customerReturnQuantity,
                       String restockLocationCode, UUID scrapAdjustmentId, UUID customerReturnAdjustmentId,
                       int replacementQuantity, int creditQuantity, BigDecimal creditAmount,
                       UUID replacementOrderLineId) {
    }

    /** A photo; its bytes come from {@code GET .../evidence/{evidenceId}/download-url}, never from this response. */
    public record Evidence(UUID id, @Schema(example = "CLAIM") String stage, String originalName, String contentType,
                           long sizeBytes, UUID uploadedBy, Instant uploadedAt) {
    }

    public record Discrepancy(UUID id, UUID lineId, @Schema(example = "SHORT") String kind, String sku, int quantity,
                              String note, UUID recordedBy, Instant recordedAt) {
    }

    public record HistoryEntry(String from, String to, String reason, UUID actorId, Instant occurredAt) {
    }
}
