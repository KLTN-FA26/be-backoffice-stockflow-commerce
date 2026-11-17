package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One return as the back office sees it (SCRUM-243; team plan 4D-3): every step, who took it and why, the photos,
 * what came back on the return receipt, the refund, and the status history. Grouped by step so a screen shows each when it has happened.
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
        @Schema(example = "REFUND") String requestedResolution,
        @Schema(example = "PORTAL") String channel,
        UUID requestedBy,
        Instant requestedAt,
        @Schema(description = "Why Sales accepted the claim outside the return window; null within it")
        String lateClaimReason,
        String currency,
        Review review,
        Receipt receipt,
        Resolution resolution,
        UUID cancelledBy,
        String cancellationReason,
        Instant closedAt,
        List<Line> lines,
        List<Evidence> evidence,
        List<Discrepancy> discrepancies,
        List<HistoryEntry> history,
        long version) implements ReturnView {

    /** Sales' decision; {@code returnDeadline}: the goods must arrive by then, or the return expires. */
    public record Review(UUID decidedBy, Instant decidedAt, String note, String rejectionReason, Boolean returnRequired,
                         Instant returnDeadline) {
    }

    /**
     * The goods back: the return receipt that took them ({@code goodsReceiptId}, under
     * {@code /api/v1/goods-receipts/returns}), who confirmed it and when, and the quarantine area they are in.
     */
    public record Receipt(UUID receivedBy, Instant receivedAt, String locationCode, UUID goodsReceiptId) {
    }

    /**
     * The refund. {@code settlementAmount}: what the units refunded are worth with their VAT; settled by payment as
     * {@code offsetAmount} (taken off what the order's installments still owed) + {@code refundedAmount} (paid already,
     * to be refunded - the accountant records the transfer). {@code vatAdjustmentRequired}: accounting issues an
     * adjustment invoice (TT 91/2026/TT-BTC Điều 10 khoản 5 điểm c.1).
     */
    public record Resolution(@Schema(example = "REFUND") String moneyMode, BigDecimal settlementAmount, UUID proposedBy,
                             Instant proposedAt, String note, UUID settledBy, Instant settledAt, BigDecimal offsetAmount,
                             BigDecimal refundedAmount, boolean vatAdjustmentRequired) {
    }

    /**
     * One order line of the return: asked for, approved, the units of its SKU that came back, and those refunded with
     * their value (VAT included).
     */
    public record Line(UUID id, UUID orderLineId, String sku, boolean customPrinted, BigDecimal unitPrice,
                       int requestedQuantity, Integer approvedQuantity, Integer receivedQuantity, int refundQuantity,
                       BigDecimal refundAmount) {
    }

    /**
     * A photo. {@code thumbnailUrl} is a short-lived link to a small copy, set on the detail view (null in other answers,
     * or for a photo without one); the photo itself comes from {@code GET .../evidence/{evidenceId}/download-url}.
     */
    public record Evidence(UUID id, @Schema(example = "CLAIM") String stage, String originalName, String contentType,
                           long sizeBytes, UUID uploadedBy, Instant uploadedAt, String thumbnailUrl) {
    }

    public record Discrepancy(UUID id, UUID lineId, @Schema(example = "SHORT") String kind, String sku, int quantity,
                              String note, UUID recordedBy, Instant recordedAt) {
    }

    public record HistoryEntry(String from, String to, String reason, UUID actorId, Instant occurredAt) {
    }
}
