package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One of the signed-in customer's own returns, as the portal shows it: what was asked, approved, received and
 * refunded, the deadline to send goods back, why it was refused. Never who in the shop did what, nor
 * their internal notes - the customer reads the decision, not the desk it came from.
 */
@Schema(name = "CustomerReturn")
public record CustomerReturnResponse(
        UUID id,
        @Schema(example = "RMA-20261025-0001") String rmaNumber,
        UUID orderId,
        String orderNumber,
        @Schema(description = "The delivery the returned goods came on; null for an order delivered at once")
        UUID shipmentId,
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
        @Schema(description = "Paid already and to be refunded, once a second person approved the refund")
        BigDecimal refundedAmount,
        @Schema(description = "Taken off what the order's installments still owed, once the refund was approved")
        BigDecimal offsetAmount,
        List<Line> lines,
        List<Discrepancy> discrepancies,
        List<Evidence> evidence,
        List<Step> history,
        long version,
        @Schema(description = "How the return ended once RESOLVED (WF-05 Q-4): REFUNDED, REFUSED (\"processed - no "
                + "refund\", for refusalReason) or NOTHING_TO_REFUND; null before", allowableValues = {"REFUNDED",
                "REFUSED", "NOTHING_TO_REFUND"})
        String outcome,
        @Schema(description = "Why goods that came back were not refunded, once RESOLVED: CUSTOMER_DAMAGE, "
                + "NOT_DEFECTIVE, OUT_OF_POLICY or OTHER")
        String refusalReason,
        @Schema(description = "refusalReason as the customer reads it (Vietnamese)", example = "Hư hỏng do bảo quản/sử dụng")
        String refusalReasonLabel,
        @Schema(description = "The note Sales wrote for the customer beside the reason")
        String refusalNote,
        @Schema(description = "Whether the warehouse is counting a parcel of it on a DRAFT return receipt now (WF-05 "
                + "BE-23): the return cannot be withdrawn meanwhile (409 RETURN_RECEIPT_DRAFT_OPEN), so the portal "
                + "hides the withdraw button")
        boolean receiptDraftOpen) implements ReturnView {

    public record Line(UUID id, UUID orderLineId, String sku, int requestedQuantity, Integer approvedQuantity,
                       Integer receivedQuantity, int refundQuantity, BigDecimal refundAmount) {
    }

    public record Discrepancy(String kind, String sku, int quantity) {
    }

    /** A photo of the claim; {@code thumbnailUrl} as on the back-office detail. */
    public record Evidence(UUID id, String stage, String originalName, Instant uploadedAt, String thumbnailUrl) {
    }

    public record Step(String status, Instant occurredAt) {
    }
}
