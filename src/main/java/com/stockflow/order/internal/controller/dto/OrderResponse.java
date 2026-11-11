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

        @Schema(description = "The warehouse the order was released from; null until released (SCRUM-423)")
        UUID warehouseId,

        Instant releasedAt,

        @Schema(description = "Set when the order is CANCELLED")
        Cancellation cancellation,

        @Schema(example = "ADDRESS", description = "Staff only. Set while ON_HOLD: CREDIT, PAYMENT, ADDRESS, "
                + "INVENTORY, CUSTOMER, FRAUD or OTHER (G3-2); null for a customer, who gets holdReasonPublic")
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

        @Schema(description = "The total less the units closed out of a partly fulfilled order")
        BigDecimal effectiveTotal,

        @Schema(description = "What the order itself knows must be paid before the goods may leave: 0 since 4B-10 - "
                + "the handover reads the contract's installments (GET /api/v1/installments/orders/{orderId}, "
                + "dueBeforeHandover)")
        BigDecimal amountDueBeforeHandover,

        @Schema(description = "The shipments handed to the carrier, oldest first (split shipments, G4-Q3)")
        List<Shipment> shipments,

        @Schema(description = "Sales allowed the order to ship in parts (PACK-PARTIAL): packing may complete part of "
                + "it, and part of it may be handed over. False by default; set with PUT "
                + "/orders/{orderId}/partial-shipment; null on rows that do not carry it")
        Boolean allowPartialShipment,

        @Schema(deprecated = true, description = "Always true: printing is done outside the system, so an order's "
                + "goods are in stock before it is released. Kept for clients that still read it; null on rows that "
                + "do not carry it")
        Boolean printLinesReady,

        @Schema(description = "4B-3: the total before tax - goods and fee lines; totalAmount is it plus taxAmount")
        BigDecimal netAmount,

        @Schema(description = "4B-3: the VAT of every line and fee line, each rounded to the dong; 0 for an order "
                + "placed without VAT")
        BigDecimal taxAmount,

        @Schema(description = "4B-3: the fee lines - a plate fee per SKU produced for the first time, the shipping fee")
        List<Fee> fees
) {

    /** A fee line with its VAT (14 BR-17, BR-20, BR-21). */
    public record Fee(String kind, String sku, BigDecimal amount, BigDecimal taxRate, BigDecimal taxAmount) {
    }

    @Schema(name = "OrderShipment")
    public record Shipment(
            UUID shipmentId,
            @Schema(example = "HANDED_OVER", description = "HANDED_OVER, DELIVERED, DELIVERY_FAILED or RETURNED")
            String status,
            @Schema(description = "The goods' value at the order's prices") BigDecimal amount,
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
            Integer cancelledQuantity,

            @Schema(description = "SCRUM-502: the date the customer asked to receive this print line, from its quote. "
                    + "Null when none was asked or for a stock line")
            java.time.LocalDate requestedDeliveryDate,

            @Schema(description = "4B-2: cartons ordered; quantity is cartons x unitsPerCarton pieces. Null for a line "
                    + "not ordered in cartons")
            Integer cartons,

            @Schema(description = "4B-2: pieces per carton fixed on the line when it was quoted")
            Integer unitsPerCarton,

            @Schema(description = "4B-3: the VAT rate fixed on the line, a percentage; null for a line without VAT")
            BigDecimal taxRate,

            @Schema(description = "4B-3: the line's VAT, rounded to the dong; null for a line without VAT")
            BigDecimal taxAmount
    ) {

        public Line(UUID lineId, String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                    List<UUID> reservationIds, UUID designSnapshotId, String designChecksum, Integer shippedQuantity,
                    Integer deliveredQuantity, Integer cancelledQuantity, java.time.LocalDate requestedDeliveryDate,
                    Integer cartons, Integer unitsPerCarton) {
            this(lineId, sku, quantity, unitPrice, lineTotal, reservationIds, designSnapshotId, designChecksum,
                    shippedQuantity, deliveredQuantity, cancelledQuantity, requestedDeliveryDate, cartons,
                    unitsPerCarton, null, null);
        }

        public Line(UUID lineId, String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                    List<UUID> reservationIds, UUID designSnapshotId, String designChecksum, Integer shippedQuantity,
                    Integer deliveredQuantity, Integer cancelledQuantity, java.time.LocalDate requestedDeliveryDate) {
            this(lineId, sku, quantity, unitPrice, lineTotal, reservationIds, designSnapshotId, designChecksum,
                    shippedQuantity, deliveredQuantity, cancelledQuantity, requestedDeliveryDate, null, null, null,
                    null);
        }

        public Line(UUID lineId, String sku, int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                    List<UUID> reservationIds, UUID designSnapshotId, String designChecksum, Integer shippedQuantity,
                    Integer deliveredQuantity, Integer cancelledQuantity) {
            this(lineId, sku, quantity, unitPrice, lineTotal, reservationIds, designSnapshotId, designChecksum,
                    shippedQuantity, deliveredQuantity, cancelledQuantity, null);
        }
    }
}
