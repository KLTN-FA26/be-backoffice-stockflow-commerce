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
        Lifecycle lifecycle
) {

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
     * @param amountDueOnDelivery    what carriers are to collect on delivery (G2-3, ON_DELIVERY): the balance not
     *                               yet paid; 0 for every other policy and term
     */
    public record Lifecycle(HoldReason holdReason, OrderStatus statusBeforeHold, String holdNote, Instant packedAt,
                            Instant shippedAt, Instant deliveredAt, DeliveryFailureReason deliveryFailureReason, Instant completedAt,
                            Instant closedAt, BigDecimal billedAmount, BigDecimal effectiveTotal, BigDecimal amountDue,
                            BigDecimal amountDueBeforeHandover, List<LineProgress> lines, List<Shipment> shipments,
                            BigDecimal amountDueOnDelivery) {

        public Lifecycle {
            lines = lines == null ? List.of() : List.copyOf(lines);
            shipments = shipments == null ? List.of() : List.copyOf(shipments);
        }

        public Lifecycle(HoldReason holdReason, OrderStatus statusBeforeHold, String holdNote, Instant packedAt,
                         Instant shippedAt, Instant deliveredAt, DeliveryFailureReason deliveryFailureReason,
                         Instant completedAt, Instant closedAt, BigDecimal billedAmount, BigDecimal effectiveTotal,
                         BigDecimal amountDue, BigDecimal amountDueBeforeHandover, List<LineProgress> lines,
                         List<Shipment> shipments) {
            this(holdReason, statusBeforeHold, holdNote, packedAt, shippedAt, deliveredAt, deliveryFailureReason,
                    completedAt, closedAt, billedAmount, effectiveTotal, amountDue, amountDueBeforeHandover, lines,
                    shipments, null);
        }
    }

    /** How much of one line went out: in shipments not returned, delivered, and closed. */
    public record LineProgress(UUID lineId, int shippedQuantity, int deliveredQuantity, int cancelledQuantity) {
    }

    /** Units of one order line in one shipment. */
    public record ShipmentLine(UUID lineId, int quantity) {
    }

    /**
     * One shipment of the order, as the order knows it (from its handover).
     *
     * @param codAmount what the carrier collects on delivering it (G2-3, ON_DELIVERY): fixed at the handover, printed on
     *                  the label and the manifest (15 BR-08, 09 BR-04); 0 otherwise
     */
    public record Shipment(UUID shipmentId, OrderShipmentStatus status, BigDecimal amount, BigDecimal billedAmount,
                           Instant handedOverAt, Instant outcomeAt, DeliveryFailureReason failureReason,
                           List<ShipmentLine> lines, BigDecimal codAmount) {

        public Shipment {
            lines = lines == null ? List.of() : List.copyOf(lines);
            codAmount = codAmount == null ? BigDecimal.ZERO : codAmount;
        }

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
     */
    public record LineSummary(
            UUID lineId,
            String sku,
            int quantity,
            Money unitPrice,
            Money lineTotal,
            List<UUID> reservationIds,
            UUID designSnapshotId,
            String designChecksum
    ) {

        public LineSummary(UUID lineId, String sku, int quantity, Money unitPrice,
                           Money lineTotal, List<UUID> reservationIds) {
            this(lineId, sku, quantity, unitPrice, lineTotal, reservationIds, null, null);
        }

        public LineSummary {
            reservationIds = reservationIds == null ? List.of() : List.copyOf(reservationIds);
        }
    }
}
