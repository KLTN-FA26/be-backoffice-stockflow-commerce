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

        @Schema(example = "UNDER_REVIEW", description = "What the customer reads: AWAITING_CONTRACT, AWAITING_PAYMENT, "
                + "UNDER_REVIEW, CONFIRMED, AWAITING_STOCK, IN_PRODUCTION (legacy), PREPARING, SHIPPING, DELIVERED, "
                + "CANCELLED or RETURNED")
        String statusGroup,

        @Schema(example = "AWAITING_CREDIT_APPROVAL", description = "Set only while the order is ON_HOLD: "
                + "AWAITING_CREDIT_APPROVAL or UNDER_REVIEW")
        String holdReasonPublic,

        BigDecimal total,

        @Schema(example = "VND")
        String currency,

        Instant placedAt,

        OrderResponse.Address shippingAddress,

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
                + "until the first handover. Every order ships as a full truckload (09 BR-08). Nothing is tracked "
                + "between handover and the outcome staff record")
        List<Shipment> shipments,

        @Schema(description = "4B-3 (14 BR-09): the total before tax - the lines and the fee lines; total is it plus "
                + "taxAmount. An order placed without VAT has its total here and taxAmount 0")
        BigDecimal netAmount,

        @Schema(description = "4B-3 (14 BR-21): the VAT of every line and fee line, each rounded to the dong")
        BigDecimal taxAmount,

        @Schema(description = "4B-3: the plate fee of each SKU produced for the first time, and the shipping fee")
        List<Fee> fees
) {

    @Schema(name = "CustomerOrderFee")
    public record Fee(
            @Schema(example = "PLATE_FEE", description = "PLATE_FEE or SHIPPING_FEE") String kind,
            @Schema(description = "The SKU a plate fee is for; null on the shipping fee") String sku,
            BigDecimal amount,
            BigDecimal taxRate,
            BigDecimal taxAmount) {
    }

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
            @Schema(description = "Ask for a cancellation (the same endpoint answers 202)") boolean requestCancellation) {
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
            @Schema(description = "4B-2 (14 BR-08): cartons ordered; quantity is cartons x unitsPerCarton pieces. Null "
                    + "for a line not ordered in cartons")
            Integer cartons,
            @Schema(description = "4B-2: pieces per carton fixed on the line")
            Integer unitsPerCarton,
            @Schema(description = "4B-3 (14 BR-21): the line's VAT rate; null on a line placed without VAT")
            BigDecimal taxRate,
            @Schema(description = "4B-3: the line's VAT, lineTotal x taxRate rounded to the dong")
            BigDecimal taxAmount) {
    }

    @Schema(name = "CustomerOrderLineProduction")
    public record Production(
            @Schema(example = "DONE", description = "NOT_REQUIRED (stock goods), NOT_STARTED (before release), DONE "
                    + "(released: printed outside the system and in stock) or CANCELLED")
            String state,
            @Schema(description = "The line's units once released; 0 before")
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
