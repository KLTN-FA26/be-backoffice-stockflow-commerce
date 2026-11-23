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
        @Schema(description = "The delivered shipment the return is against (WF-05 TH-1); null for an order "
                + "delivered without shipments")
        UUID shipmentId,
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
        long version,
        @Schema(description = "How a RESOLVED return ended (WF-05 Q-4): REFUNDED (units refunded), REFUSED (goods came "
                + "back usable and none was refunded, for refusalReason) or NOTHING_TO_REFUND (nothing approved came "
                + "back usable); null before RESOLVED", allowableValues = {"REFUNDED", "REFUSED", "NOTHING_TO_REFUND"})
        String outcome,
        @Schema(description = "Why units that came back are withheld, on a proposal or a resolution: CUSTOMER_DAMAGE, "
                + "NOT_DEFECTIVE, OUT_OF_POLICY or OTHER; null when nothing is")
        String refusalReason,
        @Schema(description = "The note the customer reads beside refusalReason")
        String refusalNote,
        @Schema(description = "Whether a return receipt may be opened for it now (WF-05 Q-11): approved and its refund "
                + "not settled")
        boolean receiptOpenable,
        @Schema(description = "Whether a parcel of it is being counted on a DRAFT return receipt now (WF-05 BE-23): "
                + "goods-complete, refund proposal and approval answer 409 RETURN_RECEIPT_DRAFT_OPEN meanwhile")
        boolean receiptDraftOpen) implements ReturnView {

    /** Sales' decision; {@code returnDeadline}: the goods must arrive by then, or the return expires. */
    public record Review(UUID decidedBy, Instant decidedAt, String note, String rejectionReason, Boolean returnRequired,
                         Instant returnDeadline) {
    }

    /**
     * The goods back: the first return receipt that took them ({@code goodsReceiptId}, under
     * {@code /api/v1/goods-receipts/returns}), who confirmed the latest and when, and the quarantine area it put them
     * in. {@code parcels}: every return receipt that brought goods of the return, the first first - a customer may send
     * them in several (WF-05 TH-3).
     */
    public record Receipt(UUID receivedBy, Instant receivedAt, String locationCode, UUID goodsReceiptId,
                          List<Parcel> parcels) {
    }

    /** One return receipt that brought goods of the return: its place among them (1 for the first), when and where. */
    public record Parcel(int parcel, UUID goodsReceiptId, String receiptNumber, String locationCode, UUID receivedBy,
                         Instant receivedAt) {
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
    public record Evidence(UUID id, @Schema(example = "CLAIM", description = "CLAIM, or CUSTODY: goods kept for the "
                           + "customer that the return does not take (WF-05 Q-11)") String stage, String originalName,
                           String contentType, long sizeBytes, UUID uploadedBy, Instant uploadedAt,
                           String thumbnailUrl, Custody custody) {
    }

    /**
     * What a CUSTODY photo shows (WF-05 Q-11): goods that came back and are kept for the customer - not in stock, not
     * refunded. Who took them in is the photo's {@code uploadedBy}, when its {@code uploadedAt}.
     */
    public record Custody(String sku, String lpn, int quantity, String note) {
    }

    public record Discrepancy(UUID id, UUID lineId, @Schema(example = "SHORT") String kind, String sku, int quantity,
                              String note, UUID recordedBy, Instant recordedAt) {
    }

    public record HistoryEntry(String from, String to, String reason, UUID actorId, Instant occurredAt) {
    }
}
