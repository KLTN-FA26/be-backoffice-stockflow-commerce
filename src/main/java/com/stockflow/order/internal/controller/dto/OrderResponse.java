package com.stockflow.order.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API representation of an order.
 *
 * <p>Money is flattened to {@code amount} plus {@code currency} rather than serialising
 * {@code Money} directly. JSON has no money type, and letting Jackson invent a shape for a value
 * object means the wire format changes whenever the record does.</p>
 */
@Schema(name = "Order")
public record OrderResponse(
        UUID orderId,

        @Schema(example = "SO-20260907-000431")
        String orderNumber,

        UUID customerId,

        @Schema(example = "PENDING_PAYMENT")
        String status,

        BigDecimal totalAmount,

        @Schema(example = "VND")
        String currency,

        List<Line> lines,

        @Schema(description = "When the order was placed - this order's created-timestamp equivalent")
        Instant placedAt,

        String createdBy,
        Instant lastModifiedAt,
        String lastModifiedBy,
        String contactName,
        String contactEmail,
        String contactPhone,
        Address shippingAddress,
        Address billingAddress,

        @Schema(example = "PREPAID", description = "PREPAID, DEPOSIT or CREDIT; null on list rows that do not carry it")
        String paymentTerm,

        @Schema(description = "The warehouse the order was released from; null until released (SCRUM-423)")
        UUID warehouseId,

        Instant releasedAt,

        @Schema(description = "Money received so far; null on rows that do not carry it")
        BigDecimal paidAmount,

        @Schema(description = "The deposit a DEPOSIT order needs before it is confirmed; null otherwise")
        BigDecimal depositRequired,

        Instant depositReceivedAt,
        Instant paidInFullAt,

        @Schema(example = "PARTIALLY_PAID", description = "UNPAID, PARTIALLY_PAID, ON_CREDIT or PAID (kltn-docs 15 §5.2)")
        String paymentStatus,

        @Schema(description = "A credit order's days to pay after delivery; null otherwise")
        Integer creditTermDays,

        @Schema(description = "Set when the order is CANCELLED")
        Cancellation cancellation,

        @Schema(example = "ADDRESS", description = "Staff only. Set while ON_HOLD: CREDIT, DESIGN_MISMATCH, PAYMENT, "
                + "ADDRESS, INVENTORY, CUSTOMER, FRAUD or OTHER (G3-2); null for a customer, who gets holdReasonPublic")
        String holdReason,

        @Schema(example = "PICKING", description = "Staff only. Where resolving the hold goes back to; null for a "
                + "credit hold")
        String statusBeforeHold,

        @Schema(example = "UNDER_REVIEW", description = "Set while ON_HOLD, for everyone: AWAITING_CREDIT_APPROVAL or "
                + "UNDER_REVIEW - the wording a customer may read")
        String holdReasonPublic,

        @Schema(description = "What the coordinator wrote when holding it; staff only, null for a customer")
        String holdNote,

        @Schema(description = "When a package of the order was first completed: from then on it is returned, not "
                + "cancelled")
        Instant packedAt,

        @Schema(description = "When the order last became SHIPPED")
        Instant shippedAt,

        @Schema(description = "When every shipment had arrived (the order became DELIVERED)")
        Instant deliveredAt,

        @Schema(example = "CUSTOMER_ABSENT", description = "Set while DELIVERY_FAILED: CUSTOMER_ABSENT, REFUSED, "
                + "WRONG_ADDRESS, LOST, DAMAGED or OTHER")
        String deliveryFailureReason,

        Instant completedAt,
        Instant closedAt,

        @Schema(description = "What deliveries billed as receivables (a credit order)")
        BigDecimal billedAmount,

        @Schema(description = "The total less the units closed out of a partly fulfilled order")
        BigDecimal effectiveTotal,

        @Schema(description = "What the customer still owes on the order (effectiveTotal - paidAmount, never below 0)")
        BigDecimal amountDue,

        @Schema(description = "What must be paid before the goods may leave (G3-Q4): packing warns, handover refuses "
                + "while it is above 0")
        BigDecimal amountDueBeforeHandover,

        @Schema(description = "The shipments handed to the carrier, oldest first (split shipments, G4-Q3)")
        List<Shipment> shipments
) {

    @Schema(name = "OrderShipment")
    public record Shipment(
            UUID shipmentId,
            @Schema(example = "HANDED_OVER", description = "HANDED_OVER, DELIVERED, DELIVERY_FAILED or RETURNED")
            String status,
            @Schema(description = "The goods' value at the order's prices") BigDecimal amount,
            @Schema(description = "What its delivery billed a credit order") BigDecimal billedAmount,
            Instant handedOverAt,
            Instant outcomeAt,
            String failureReason,
            List<ShipmentLine> lines) {
    }

    @Schema(name = "OrderShipmentLine")
    public record ShipmentLine(UUID lineId, int quantity) {
    }

    @Schema(name = "OrderCancellation")
    public record Cancellation(
            @Schema(example = "CUSTOMER_REQUEST") String reasonCode,
            String reason,
            @Schema(description = "Kept from the money received for work already done") BigDecimal retainedAmount,
            @Schema(description = "Why an agreed amount was kept (decision Q2)") String feeReason,
            @Schema(description = "What was agreed") String feeDetail,
            @Schema(description = "The agreed amount; above retainedAmount the rest is owed as a receivable")
            BigDecimal feeAmount) {
    }

    public record Address(String recipientName, String phone, String line1, String line2,
                          String wardCode, String wardName, String provinceCode,
                          String provinceName, String countryCode, String postalCode) {
    }

    @Schema(name = "OrderLine")
    public record Line(
            UUID lineId,
            String sku,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,

            @Schema(description = "The stock holds backing this line - one per lot it is drawn from")
            List<UUID> reservationIds,
            UUID designSnapshotId,
            String designChecksum,

            @Schema(description = "Units in shipments still with the carrier or the customer")
            Integer shippedQuantity,

            @Schema(description = "Units in delivered shipments")
            Integer deliveredQuantity,

            @Schema(description = "Units closed out of a partly fulfilled order: they will not be delivered")
            Integer cancelledQuantity
    ) {
    }
}
