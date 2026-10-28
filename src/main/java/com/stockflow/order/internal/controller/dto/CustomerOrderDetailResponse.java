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
        CancellationRequest cancellationRequest,

        @Schema(description = "G4-2a: the shipments handed over to a carrier, oldest first (kltn-docs 09 BR-05); empty "
                + "until the first handover. Nothing is tracked between handover and the outcome staff record")
        List<Shipment> shipments,

        @Schema(example = "PARCEL", description = "SCRUM-209: how the order travels, PARCEL or FULL_TRUCKLOAD, as the "
                + "coordinator set it; null when not set. Shipments carry their own mode too")
        String deliveryMode
) {

    @Schema(name = "CustomerOrderShipment")
    public record Shipment(
            @Schema(example = "SO-20261021-000123-S1", description = "Null for a shipment recorded before 2026-10-21")
            String shipmentCode,
            @Schema(example = "HANDED_OVER", description = "HANDED_OVER (with the carrier), DELIVERED, DELIVERY_FAILED or "
                    + "RETURNED")
            String status,
            @Schema(example = "2", description = "Parcels in it; 0 when not recorded")
            int packageCount,
            @Schema(example = "Giao Hàng Nhanh")
            String carrierName,
            @Schema(example = "PARCEL", description = "PARCEL or FULL_TRUCKLOAD")
            String deliveryMode,
            Instant handedOverAt,
            @Schema(description = "When staff recorded the latest outcome; null while with the carrier")
            Instant outcomeAt,
            @Schema(example = "CUSTOMER_ABSENT", description = "Why a delivery failed: CUSTOMER_ABSENT, REFUSED, "
                    + "WRONG_ADDRESS, LOST, DAMAGED or OTHER; stays after a retry delivered it")
            String failureReason) {
    }

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
            @Schema(description = "Order the same again by a new draft quote (POST /orders/{id}/reorder): true only for an "
                    + "order that came from a quote. An order bought at the price list is false: the portal puts its "
                    + "lines back in the basket instead") boolean reorder) {
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
            Production production,
            @Schema(description = "Where this SKU is on sale in the storefront today (/catalog/{slug}); null when it "
                    + "is not on sale now or the line is a design line. Today's catalogue, for putting the line back "
                    + "in a basket (reorder)")
            String productSlug,
            @Schema(description = "The product's published title today; null as productSlug")
            String productTitle,
            @Schema(description = "The product's primary public image today; null without one or as productSlug")
            String productImageUrl) {
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
