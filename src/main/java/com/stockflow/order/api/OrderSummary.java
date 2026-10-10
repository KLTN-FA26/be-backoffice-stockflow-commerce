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
        Cancellation cancellation
) {

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
     */
    public record Payment(BigDecimal paidAmount, BigDecimal depositRequired, Instant depositReceivedAt,
                          Instant paidInFullAt, PaymentStatus status) {
    }

    /** Why and on what terms a cancelled order was cancelled; null while it is not. */
    public record Cancellation(CancellationReasonCode reasonCode, String reason, BigDecimal retainedAmount) {
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
