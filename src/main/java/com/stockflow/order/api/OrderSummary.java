package com.stockflow.order.api;

import com.stockflow.common.domain.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model of an order, for other modules and for the API.
 *
 * <p>Flat and immutable. Handing out the {@code Order} aggregate would let a caller invoke
 * {@code confirmPayment()} on it outside a transaction, which is exactly the kind of bypass the
 * module boundary exists to prevent.</p>
 */
public record OrderSummary(
        UUID orderId,
        String orderNumber,
        UUID customerId,
        OrderStatus status,
        Money total,
        List<LineSummary> lines,
        Instant placedAt,
        String createdBy,
        Instant lastModifiedAt,
        String lastModifiedBy,
        String contactName,
        String contactEmail,
        String contactPhone,
        AddressSummary shippingAddress,
        AddressSummary billingAddress,
        PaymentTerm paymentTerm,
        UUID warehouseId,
        Instant releasedAt,
        Payment payment,
        Cancellation cancellation,
        Lifecycle lifecycle,
        Charges charges
) {

    /** Without the charges block (4B-3): the total is all there is, with no VAT or fee line. */
    public OrderSummary(UUID orderId, String orderNumber, UUID customerId, OrderStatus status, Money total,
                        List<LineSummary> lines, Instant placedAt, String createdBy, Instant lastModifiedAt,
                        String lastModifiedBy, String contactName, String contactEmail, String contactPhone,
                        AddressSummary shippingAddress, AddressSummary billingAddress, PaymentTerm paymentTerm,
                        UUID warehouseId, Instant releasedAt, Payment payment, Cancellation cancellation,
                        Lifecycle lifecycle) {
        this(orderId, orderNumber, customerId, status, total, lines, placedAt, createdBy, lastModifiedAt,
                lastModifiedBy, contactName, contactEmail, contactPhone, shippingAddress, billingAddress,
                paymentTerm, warehouseId, releasedAt, payment, cancellation, lifecycle, null);
    }

    /**
     * The order's three totals and its fee lines (4B-3; kltn-docs 14 BR-09): before tax (goods and fees), the VAT, and
     * {@link #total()} with it.
     */
    public record Charges(Money netTotal, Money taxTotal, List<FeeSummary> fees) {

        /** {@code fees} null: not loaded (a list row carries the totals, not the fee lines). */
        public Charges {
            fees = fees == null ? null : List.copyOf(fees);
        }
    }

    /** A fee line: the plate fee of a SKU produced for the first time, or the shipping fee; its VAT. */
    public record FeeSummary(FeeKind kind, String sku, Money amount, java.math.BigDecimal taxRate, Money taxAmount) {
    }

    /** The total before tax: {@link Charges#netTotal()}, or the total of an order without charges. */
    public Money netTotal() {
        return charges == null ? total : charges.netTotal();
    }

    /** Without the lifecycle block (G3-2): callers written before it. */
    public OrderSummary(UUID orderId, String orderNumber, UUID customerId, OrderStatus status, Money total,
                        List<LineSummary> lines, Instant placedAt, String createdBy, Instant lastModifiedAt,
                        String lastModifiedBy, String contactName, String contactEmail, String contactPhone,
                        AddressSummary shippingAddress, AddressSummary billingAddress, PaymentTerm paymentTerm,
                        UUID warehouseId, Instant releasedAt, Payment payment, Cancellation cancellation) {
        this(orderId, orderNumber, customerId, status, total, lines, placedAt, createdBy, lastModifiedAt,
                lastModifiedBy, contactName, contactEmail, contactPhone, shippingAddress, billingAddress,
                paymentTerm, warehouseId, releasedAt, payment, cancellation, null);
    }

    /**
     * Hold, milestones, shipments and the money of a partly fulfilled order (G3-2; split shipments, G4-Q3). Null on
     * rows that do not carry it.
     *
     * @param holdReason             set while ON_HOLD
     * @param statusBeforeHold       where resolving the hold goes back to; null for a credit hold
     * @param holdNote               what the coordinator wrote; staff only
     * @param packedAt               when a package of the order was first completed: from then on it is not cancelled
     * @param deliveryFailureReason  set while DELIVERY_FAILED
     * @param billedAmount           what deliveries billed as receivables (a credit order)
     * @param effectiveTotal         the total less the units closed out of a partly fulfilled order
     * @param amountDue              what the customer still owes on it, never below zero
     * @param amountDueBeforeHandover what must be paid before the goods may leave (G3-Q4): fulfillment warns at
     *                               packing and the handover is refused while it is above zero
     * @param allowPartialShipment   Sales allowed the order to ship in parts (PACK-PARTIAL, lead's decision
     *                               2026-10-21): packing may complete part of it and part of it may be handed over
     * @param goodsLeftAt            SCRUM-392: when goods of the order first left on a load plan's vehicle, before a
     *                               shipment of it is handed over - from then it is not cancelled (review of #162)
     * @param firstLabeledAt         4C-6: when the order's first shipping carton was labelled - from then it is not
     *                               cancelled and its delivery address does not change (kltn-docs 08 BR-04)
     */
    public record Lifecycle(HoldReason holdReason, OrderStatus statusBeforeHold, String holdNote, Instant packedAt,
                            Instant shippedAt, Instant deliveredAt, DeliveryFailureReason deliveryFailureReason, Instant completedAt,
                            Instant closedAt, BigDecimal billedAmount, BigDecimal effectiveTotal, BigDecimal amountDue,
                            BigDecimal amountDueBeforeHandover, List<LineProgress> lines, List<Shipment> shipments,
                            boolean allowPartialShipment, Instant goodsLeftAt, Instant firstLabeledAt) {

        public Lifecycle {
            lines = lines == null ? List.of() : List.copyOf(lines);
            shipments = shipments == null ? List.of() : List.copyOf(shipments);
        }

        /** Without the first label (callers written before 4C-6): no carton labelled. */
        public Lifecycle(HoldReason holdReason, OrderStatus statusBeforeHold, String holdNote, Instant packedAt,
                         Instant shippedAt, Instant deliveredAt, DeliveryFailureReason deliveryFailureReason,
                         Instant completedAt, Instant closedAt, BigDecimal billedAmount, BigDecimal effectiveTotal,
                         BigDecimal amountDue, BigDecimal amountDueBeforeHandover, List<LineProgress> lines,
                         List<Shipment> shipments, boolean allowPartialShipment, Instant goodsLeftAt) {
            this(holdReason, statusBeforeHold, holdNote, packedAt, shippedAt, deliveredAt, deliveryFailureReason,
                    completedAt, closedAt, billedAmount, effectiveTotal, amountDue, amountDueBeforeHandover, lines,
                    shipments, allowPartialShipment, goodsLeftAt, null);
        }

        /**
         * The order is locked against cancellation and a change of delivery address: a carton labelled (08 BR-04), or a
         * package the repack station completed before 4C-6.
         */
        public boolean packedForGood() {
            return firstLabeledAt != null || packedAt != null;
        }

        /** Without goodsLeftAt (callers written before SCRUM-392): no goods left on a vehicle yet. */
        public Lifecycle(HoldReason holdReason, OrderStatus statusBeforeHold, String holdNote, Instant packedAt,
                         Instant shippedAt, Instant deliveredAt, DeliveryFailureReason deliveryFailureReason,
                         Instant completedAt, Instant closedAt, BigDecimal billedAmount, BigDecimal effectiveTotal,
                         BigDecimal amountDue, BigDecimal amountDueBeforeHandover, List<LineProgress> lines,
                         List<Shipment> shipments, boolean allowPartialShipment) {
            this(holdReason, statusBeforeHold, holdNote, packedAt, shippedAt, deliveredAt, deliveryFailureReason,
                    completedAt, closedAt, billedAmount, effectiveTotal, amountDue, amountDueBeforeHandover, lines,
                    shipments, allowPartialShipment, null);
        }

        /** Without the partial-shipment block (callers written before PACK-PARTIAL): not allowed. */
        public Lifecycle(HoldReason holdReason, OrderStatus statusBeforeHold, String holdNote, Instant packedAt,
                         Instant shippedAt, Instant deliveredAt, DeliveryFailureReason deliveryFailureReason,
                         Instant completedAt, Instant closedAt, BigDecimal billedAmount, BigDecimal effectiveTotal,
                         BigDecimal amountDue, BigDecimal amountDueBeforeHandover, List<LineProgress> lines,
                         List<Shipment> shipments) {
            this(holdReason, statusBeforeHold, holdNote, packedAt, shippedAt, deliveredAt, deliveryFailureReason,
                    completedAt, closedAt, billedAmount, effectiveTotal, amountDue, amountDueBeforeHandover, lines,
                    shipments, false);
        }
    }

    /** How much of one line went out: in shipments not returned, delivered, and closed. */
    public record LineProgress(UUID lineId, int shippedQuantity, int deliveredQuantity, int cancelledQuantity) {
    }

    /** Units of one order line in one shipment. */
    public record ShipmentLine(UUID lineId, int quantity) {
    }

    /**
     * One shipment of the order, as the order knows it (from its handover). Nothing is collected on delivery
     * (kltn-docs 09 BR-08).
     *
     * @param carriage number, parcels and carrier as fulfillment handed it over (G4-2a); null before that
     */
    public record Shipment(UUID shipmentId, OrderShipmentStatus status, BigDecimal amount, BigDecimal billedAmount,
                           Instant handedOverAt, Instant outcomeAt, DeliveryFailureReason failureReason,
                           List<ShipmentLine> lines, ShipmentHandover.Carriage carriage) {

        public Shipment {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        /** Without the carriage (G4-2a): callers written before it. */
        public Shipment(UUID shipmentId, OrderShipmentStatus status, BigDecimal amount, BigDecimal billedAmount,
                        Instant handedOverAt, Instant outcomeAt, DeliveryFailureReason failureReason,
                        List<ShipmentLine> lines) {
            this(shipmentId, status, amount, billedAmount, handedOverAt, outcomeAt, failureReason, lines, null);
        }
    }

    /** With the release block but without money and cancellation: list rows of other modules. */
    public OrderSummary(UUID orderId, String orderNumber, UUID customerId, OrderStatus status, Money total,
                        List<LineSummary> lines, Instant placedAt, String createdBy, Instant lastModifiedAt,
                        String lastModifiedBy, String contactName, String contactEmail, String contactPhone,
                        AddressSummary shippingAddress, AddressSummary billingAddress, PaymentTerm paymentTerm,
                        UUID warehouseId, Instant releasedAt) {
        this(orderId, orderNumber, customerId, status, total, lines, placedAt, createdBy, lastModifiedAt,
                lastModifiedBy, contactName, contactEmail, contactPhone, shippingAddress, billingAddress,
                paymentTerm, warehouseId, releasedAt, null, null);
    }

    /**
     * The money side of the order (SCRUM-460, kltn-docs 15 §5.2).
     *
     * @param depositRequired null unless the order is on DEPOSIT terms
     * @param balancePolicy   null unless the order is on DEPOSIT terms: how its balance is collected (G2-3)
     */
    public record Payment(BigDecimal paidAmount, BigDecimal depositRequired, Instant depositReceivedAt,
                          Instant paidInFullAt, PaymentStatus status, Integer creditTermDays,
                          BalancePolicy balancePolicy) {

        public Payment(BigDecimal paidAmount, BigDecimal depositRequired, Instant depositReceivedAt,
                       Instant paidInFullAt, PaymentStatus status, Integer creditTermDays) {
            this(paidAmount, depositRequired, depositReceivedAt, paidInFullAt, status, creditTermDays, null);
        }

        public Payment(BigDecimal paidAmount, BigDecimal depositRequired, Instant depositReceivedAt,
                       Instant paidInFullAt, PaymentStatus status) {
            this(paidAmount, depositRequired, depositReceivedAt, paidInFullAt, status, null, null);
        }
    }

    /** Why and on what terms a cancelled order was cancelled; null while it is not. */
    /**
     * @param retainedAmount what was kept of the money received
     * @param feeAmount      the amount agreed with the customer (Q2); above {@code retainedAmount} the rest is owed
     */
    public record Cancellation(CancellationReasonCode reasonCode, String reason, BigDecimal retainedAmount,
                               String feeReason, String feeDetail, BigDecimal feeAmount) {

        public Cancellation(CancellationReasonCode reasonCode, String reason, BigDecimal retainedAmount) {
            this(reasonCode, reason, retainedAmount, null, null, null);
        }
    }

    public OrderSummary(UUID orderId, String orderNumber, UUID customerId, OrderStatus status,
                        Money total, List<LineSummary> lines, Instant placedAt) {
        this(orderId, orderNumber, customerId, status, total, lines, placedAt,
                null, null, null, null, null, null, null, null);
    }

    /** Without the release block: rows and callers that do not show where an order ships from. */
    public OrderSummary(UUID orderId, String orderNumber, UUID customerId, OrderStatus status, Money total,
                        List<LineSummary> lines, Instant placedAt, String createdBy, Instant lastModifiedAt,
                        String lastModifiedBy, String contactName, String contactEmail, String contactPhone,
                        AddressSummary shippingAddress, AddressSummary billingAddress) {
        this(orderId, orderNumber, customerId, status, total, lines, placedAt, createdBy, lastModifiedAt,
                lastModifiedBy, contactName, contactEmail, contactPhone, shippingAddress, billingAddress,
                null, null, null);
    }

    public record AddressSummary(String recipientName, String phone, String line1, String line2,
                                 String wardCode, String wardName, String provinceCode,
                                 String provinceName, String countryCode, String postalCode) {
    }

    /**
     * @param reservationIds every hold inventory gave back for this line — one per lot it was drawn
     *                       from — kept so cancellation knows what to release
     * @param requestedDeliveryDate the date the customer asked to receive a print line (SCRUM-502); null when
     *                       none was asked
     * @param cartons        cartons ordered (4B-2, kltn-docs 14 BR-08): {@code quantity} is cartons x
     *                       {@code unitsPerCarton} pieces; both null for a line not ordered in cartons
     * @param taxRate        the VAT rate fixed on the line (4B-3, 14 BR-21); null with {@code taxAmount} for a line
     *                       without VAT
     */
    public record LineSummary(
            UUID lineId,
            String sku,
            int quantity,
            Money unitPrice,
            Money lineTotal,
            List<UUID> reservationIds,
            UUID designSnapshotId,
            String designChecksum,
            java.time.LocalDate requestedDeliveryDate,
            Integer cartons,
            Integer unitsPerCarton,
            java.math.BigDecimal taxRate,
            Money taxAmount
    ) {

        /** Without VAT. */
        public LineSummary(UUID lineId, String sku, int quantity, Money unitPrice, Money lineTotal,
                           List<UUID> reservationIds, UUID designSnapshotId, String designChecksum,
                           java.time.LocalDate requestedDeliveryDate, Integer cartons, Integer unitsPerCarton) {
            this(lineId, sku, quantity, unitPrice, lineTotal, reservationIds, designSnapshotId, designChecksum,
                    requestedDeliveryDate, cartons, unitsPerCarton, null, null);
        }

        /** Not ordered in cartons. */
        public LineSummary(UUID lineId, String sku, int quantity, Money unitPrice, Money lineTotal,
                           List<UUID> reservationIds, UUID designSnapshotId, String designChecksum,
                           java.time.LocalDate requestedDeliveryDate) {
            this(lineId, sku, quantity, unitPrice, lineTotal, reservationIds, designSnapshotId, designChecksum,
                    requestedDeliveryDate, null, null);
        }

        public LineSummary(UUID lineId, String sku, int quantity, Money unitPrice,
                           Money lineTotal, List<UUID> reservationIds) {
            this(lineId, sku, quantity, unitPrice, lineTotal, reservationIds, null, null, null, null, null);
        }

        /** Without a requested delivery date (SCRUM-502). */
        public LineSummary(UUID lineId, String sku, int quantity, Money unitPrice, Money lineTotal,
                           List<UUID> reservationIds, UUID designSnapshotId, String designChecksum) {
            this(lineId, sku, quantity, unitPrice, lineTotal, reservationIds, designSnapshotId, designChecksum, null,
                    null, null);
        }

        public LineSummary {
            reservationIds = reservationIds == null ? List.of() : List.copyOf(reservationIds);
        }
    }
}
