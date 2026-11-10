package com.stockflow.order.api;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;

import java.util.List;
import java.util.UUID;

/**
 * Intent: place an order - an accepted quote's (the quote service converts it), or a test's fixture until 4B-10. There
 * is no cart and no checkout any more (team plan 5-2).
 *
 * <p>Records with compact-constructor validation, so an instance that exists is structurally
 * valid. The caller cannot hand the service a command with zero lines and discover the problem
 * three layers down.</p>
 */
public record PlaceOrderCommand(
        UUID requestId,
        UUID customerId,
        UUID shippingAddressId,
        UUID billingAddressId,
        boolean snapshotCustomer,
        List<Line> lines,
        List<Fee> fees
) {

    /**
     * Without fee lines (before 4B-3, and every checkout). No payment terms since 4B-10: an order is paid by its sales
     * contract's installments.
     */
    public PlaceOrderCommand(UUID requestId, UUID customerId, UUID shippingAddressId, UUID billingAddressId,
                             boolean snapshotCustomer, List<Line> lines) {
        this(requestId, customerId, shippingAddressId, billingAddressId, snapshotCustomer, lines, List.of());
    }

    /** Compatibility constructor for trusted internal callers and existing integrations. */
    public PlaceOrderCommand(UUID requestId, UUID customerId, List<Line> lines) {
        this(requestId, customerId, null, null, false, lines);
    }

    public PlaceOrderCommand {
        if (requestId == null) throw new IllegalArgumentException("requestId is required");
        if (customerId == null) throw new IllegalArgumentException("customerId is required");
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("An order needs at least one line");
        }
        lines = List.copyOf(lines);
        fees = fees == null ? List.of() : List.copyOf(fees);
    }

    /**
     * A fee line of the accepted quote (4B-3; kltn-docs 14 BR-09, BR-17, BR-20): its amount and the VAT rate fixed on it.
     *
     * @param sku the SKU a plate fee is for; null on the shipping fee
     */
    public record Fee(FeeKind kind, Sku sku, Money amount, java.math.BigDecimal taxRate) {

        public Fee {
            if (kind == null) throw new IllegalArgumentException("kind is required");
            if (amount == null || amount.isNegative()) throw new IllegalArgumentException("amount is not negative");
            if (taxRate == null) throw new IllegalArgumentException("taxRate is required");
            if ((kind == FeeKind.PLATE_FEE) != (sku != null)) {
                throw new IllegalArgumentException("a plate fee names its SKU; the shipping fee names none");
            }
        }
    }

    /**
     * One line.
     *
     * @param designSnapshotId set only for print-on-demand items; identifies the frozen design the
     *                         customer approved, so a later edit to the design cannot change what
     *                         was ordered
     * @param requestedDeliveryDate the date the customer asked to receive a print line or a line in cartons
     *                         (SCRUM-502, from its quote). Null when none was asked; never set on a stock line
     * @param carton           how a line of the customer's own SKU was ordered (4B-2): {@code quantity} is its pieces;
     *                         null for any other line
     * @param taxRate          the VAT rate fixed on the accepted quote line (4B-3, 14 BR-21), a percentage; null for a
     *                         line without VAT (checkout)
     */
    public record Line(Sku sku, int quantity, Money unitPrice, UUID designSnapshotId,
                       java.time.LocalDate requestedDeliveryDate, Carton carton, java.math.BigDecimal taxRate) {

        /** Without VAT. */
        public Line(Sku sku, int quantity, Money unitPrice, UUID designSnapshotId,
                    java.time.LocalDate requestedDeliveryDate, Carton carton) {
            this(sku, quantity, unitPrice, designSnapshotId, requestedDeliveryDate, carton, null);
        }

        /** Not ordered in cartons. */
        public Line(Sku sku, int quantity, Money unitPrice, UUID designSnapshotId,
                    java.time.LocalDate requestedDeliveryDate) {
            this(sku, quantity, unitPrice, designSnapshotId, requestedDeliveryDate, null, null);
        }

        /** Without a requested delivery date. */
        public Line(Sku sku, int quantity, Money unitPrice, UUID designSnapshotId) {
            this(sku, quantity, unitPrice, designSnapshotId, null, null, null);
        }

        public Line {
            if (sku == null) throw new IllegalArgumentException("sku is required");
            if (quantity < 1) throw new IllegalArgumentException("quantity must be at least 1");
            if (unitPrice == null) throw new IllegalArgumentException("unitPrice is required");
            if (unitPrice.isNegative()) {
                throw new IllegalArgumentException("unitPrice must not be negative");
            }
            if (requestedDeliveryDate != null && designSnapshotId == null && carton == null) {
                throw new IllegalArgumentException("only a print line or a line in cartons has a requested delivery date");
            }
            if (carton != null && (long) carton.cartons() * carton.unitsPerCarton() != quantity) {
                throw new IllegalArgumentException("quantity must be cartons x unitsPerCarton");
            }
        }
    }

    /**
     * Cartons of the customer's own SKU, as the accepted quote line fixed them (kltn-docs 14 BR-08).
     *
     * @param uomConversionId the SKU's carton unit
     * @param unitsPerCarton  pieces per carton fixed on the quote line
     */
    public record Carton(UUID uomConversionId, int cartons, int unitsPerCarton) {

        public Carton {
            if (uomConversionId == null) throw new IllegalArgumentException("uomConversionId is required");
            if (cartons < 1) throw new IllegalArgumentException("cartons must be at least 1");
            if (unitsPerCarton < 2) throw new IllegalArgumentException("a carton holds more than one piece");
        }
    }
}
