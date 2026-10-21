package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One of the signed-in customer's orders: status, timeline, what is printed and where delivery stands
 * (SCRUM-245/264).
 *
 * <p>Deliberately not {@link OrderResponse}: it leaves out everything internal to the shop - stock
 * reservations, design checksums, who created or changed the order, the warehouse, the contact block
 * and the free-text cancellation reason.</p>
 */
@Schema(name = "CustomerOrderDetail")
public record CustomerOrderDetailResponse(
        UUID orderId,

        @Schema(example = "SO-20260907-000431")
        String orderNumber,

        @Schema(example = "ON_HOLD")
        String status,

        @Schema(example = "UNDER_REVIEW", description = "What the customer reads: AWAITING_PAYMENT, UNDER_REVIEW, "
                + "CONFIRMED, IN_PRODUCTION, PREPARING, SHIPPING, DELIVERED, CANCELLED or RETURNED")
        String statusGroup,

        @Schema(example = "AWAITING_CREDIT_APPROVAL", description = "Set only while the order is ON_HOLD: "
                + "AWAITING_CREDIT_APPROVAL or UNDER_REVIEW")
        String holdReasonPublic,

        @Schema(example = "PREPAID", description = "PREPAID, DEPOSIT or CREDIT")
        String paymentTerm,

        BigDecimal total,

        @Schema(example = "VND")
        String currency,

        Instant placedAt,

        OrderResponse.Address shippingAddress,

        Payment payment,

        List<Line> lines,

        @Schema(description = "Oldest first")
        List<TimelineEntry> timeline,

        @Schema(example = "NOT_SHIPPED", description = "NOT_SHIPPED, IN_TRANSIT, PARTIALLY_DELIVERED, DELIVERY_FAILED, "
                + "DELIVERED or RETURNED - only what staff have recorded; nothing is tracked after handover to the "
                + "carrier. Parse tolerantly: more may come")
        String deliveryState,

        @Schema(description = "What the customer may do now (G3-2): the portal shows these buttons instead of keeping "
                + "its own list of statuses")
        AllowedActions allowedActions,

        @Schema(description = "The customer's latest cancellation request and its decision; null when they never asked")
        CancellationRequest cancellationRequest
) {

    @Schema(name = "CustomerOrderCancellationRequest")
    public record CancellationRequest(
            @Schema(example = "PENDING", description = "PENDING, APPROVED or REJECTED") String status,
            @Schema(example = "CUSTOMER_REQUEST") String reasonCode,
            @Schema(description = "What the customer wrote") String note,
            Instant requestedAt,
            @Schema(description = "Set once Sales decided") Instant decidedAt,
            @Schema(description = "What Sales answered, e.g. why it was rejected") String decisionNote) {
    }

    @Schema(name = "CustomerOrderAllowedActions")
    public record AllowedActions(
            @Schema(description = "Cancel at once (POST /orders/{id}/cancellation answers 200)") boolean cancel,
            @Schema(description = "Ask for a cancellation (the same endpoint answers 202)") boolean requestCancellation,
            @Schema(description = "Order the same again (POST /orders/{id}/reorder)") boolean reorder) {
    }

    @Schema(name = "CustomerOrderPayment")
    public record Payment(
            @Schema(example = "PARTIALLY_PAID", description = "UNPAID, PARTIALLY_PAID, ON_CREDIT or PAID")
            String status,
            BigDecimal paidAmount,
            @Schema(description = "The deposit a DEPOSIT order needs before it is confirmed; null otherwise")
            BigDecimal depositRequired,
            @Schema(description = "A credit order's days to pay after delivery; null otherwise")
            Integer creditTermDays) {
    }

    @Schema(name = "CustomerOrderLine")
    public record Line(
            UUID lineId,
            String sku,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            @Schema(description = "Set for a line printed to the customer's design; null for stock goods")
            UUID designSnapshotId,
            Production production) {
    }

    @Schema(name = "CustomerOrderLineProduction")
    public record Production(
            @Schema(example = "IN_PROGRESS", description = "NOT_REQUIRED (stock goods), NOT_STARTED, IN_PROGRESS, DONE "
                    + "or CANCELLED")
            String state,
            @Schema(description = "Good units printed so far")
            int goodQuantity) {
    }

    @Schema(name = "CustomerOrderTimelineEntry")
    public record TimelineEntry(
            String status,
            String statusGroup,
            Instant occurredAt,
            @Schema(example = "CUSTOMER_REQUEST", description = "Set only on the cancellation step")
            String reasonCode) {
    }
}
