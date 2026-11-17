package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One line of an {@link Order}. An entity inside the aggregate, with no repository of its own.
 *
 * <p><b>{@code unitPrice} is copied, not looked up.</b> The price the customer agreed to is a fact
 * about this order, and it must not change when marketing edits the price list next week. This is
 * the difference between a reference and a snapshot, and getting it wrong is how invoices stop
 * matching orders.</p>
 *
 * <p>{@code reservationIds} is the one field that ties a line to another module, and it is a
 * <b>list</b> rather than a single id. A line for ten units is often drawn from two lots, and
 * inventory returns one hold per lot; keeping only the first would strand the rest, so cancelling
 * the order would release six units and leave four held until they expired. They are plain
 * {@code UUID}s: order stores what inventory handed back and passes them to
 * {@code InventoryService.release(...)} later, without ever naming an inventory type.</p>
 */
public final class OrderLine {

    private final UUID id;
    private final Sku sku;
    private final int quantity;
    private final Money unitPrice;
    private final UUID designSnapshotId;
    private String designChecksum;

    public String designChecksum() { return designChecksum; }
    public void recordDesignChecksum(String checksum) {
        if (designSnapshotId == null || checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("A confirmed design requires a SHA-256 checksum");
        }
        if (designChecksum != null && !designChecksum.equals(checksum)) {
            throw new IllegalStateException("An order's confirmed design checksum cannot change");
        }
        designChecksum = checksum;
    }

    private final List<UUID> reservationIds;

    /**
     * SCRUM-502 (PROD-DUE): the date the customer asked to receive this print line, copied from its quote when the
     * order is placed and fixed from then on. Null: none was asked.
     */
    private LocalDate requestedDeliveryDate;

    /** Units closed out of the line - the rest of a partly fulfilled order (17 edge "short pick"): not delivered. */
    private int cancelledQuantity;

    /**
     * 4B-2 (kltn-docs 14 BR-08): a line placed from a quote line of the customer's own SKU keeps how it was ordered -
     * the carton unit, the cartons and the pieces per carton fixed on the quote line; {@link #quantity()} stays in
     * pieces, cartons x pieces per carton. Null on a line placed before, or bought at checkout.
     */
    private UUID uomConversionId;
    private Integer cartons;
    private Integer unitsPerCarton;

    /**
     * 4B-3 (kltn-docs 14 BR-21): the VAT rate fixed on the line with its price, and its tax - the line's total at the
     * rate, rounded to the dong. Null on a line placed without one (before 4B-3, or bought at checkout): no VAT.
     */
    private java.math.BigDecimal taxRate;
    private Money taxAmount;

    public OrderLine(UUID id, Sku sku, int quantity, Money unitPrice,
                     UUID designSnapshotId, List<UUID> reservationIds, int cancelledQuantity) {
        this(id, sku, quantity, unitPrice, designSnapshotId, reservationIds);
        if (cancelledQuantity < 0 || cancelledQuantity > quantity) {
            throw new IllegalArgumentException("Closed units are between 0 and the line's quantity");
        }
        this.cancelledQuantity = cancelledQuantity;
    }

    public OrderLine(UUID id, Sku sku, int quantity, Money unitPrice,
                     UUID designSnapshotId, List<UUID> reservationIds) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        this.sku = java.util.Objects.requireNonNull(sku, "sku");
        this.unitPrice = java.util.Objects.requireNonNull(unitPrice, "unitPrice");
        if (quantity < 1) {
            throw new IllegalArgumentException("An order line needs at least one unit");
        }
        this.quantity = quantity;
        this.designSnapshotId = designSnapshotId;
        this.reservationIds = new ArrayList<>(reservationIds == null ? List.of() : reservationIds);
    }

    static OrderLine of(UUID id, Sku sku, int quantity, Money unitPrice, UUID designSnapshotId) {
        return new OrderLine(id, sku, quantity, unitPrice, designSnapshotId, List.of());
    }

    /**
     * Record the holds inventory granted. Package-private: only {@link Order} may set them.
     *
     * <p>Refuses a second attach rather than appending, because a line that already holds stock
     * being reserved again means a code path ran twice — and appending would silently double the
     * stock held for one line.</p>
     */
    void attachReservations(List<UUID> granted) {
        if (!this.reservationIds.isEmpty()) {
            throw new IllegalStateException(
                    "Line %s already holds reservation(s) %s".formatted(id, this.reservationIds));
        }
        if (granted == null || granted.isEmpty()) {
            throw new IllegalArgumentException("A line cannot be reserved with zero holds");
        }
        this.reservationIds.addAll(granted);
    }

    void clearReservations() {
        this.reservationIds.clear();
    }

    /** One hold gives way to the holds that replace it, in its place on the line; each kept once. */
    void replaceHold(UUID replaced, List<UUID> replacements) {
        if (replacements == null || replacements.isEmpty()) {
            throw new IllegalArgumentException("A hold is replaced by at least one hold");
        }
        int at = reservationIds.indexOf(replaced);
        if (at < 0) {
            return;
        }
        reservationIds.remove(at);
        List<UUID> fresh = replacements.stream().filter(id -> !reservationIds.contains(id)).distinct().toList();
        reservationIds.addAll(at, fresh);
    }

    public boolean isReserved() {
        return !reservationIds.isEmpty();
    }

    /**
     * 4B-7: holds added to a line of an order of a contract - its first holds at the shipping warehouse, or stock that
     * arrived after them (17 §4.2 step 3). Unlike a checkout's one attach, a line is held over time.
     */
    void holdMore(List<UUID> more) {
        if (more == null) {
            return;
        }
        more.stream().filter(id -> !reservationIds.contains(id)).distinct().forEach(reservationIds::add);
    }

    public Money lineTotal() {
        return unitPrice.times(quantity);
    }

    public UUID id() { return id; }
    public Sku sku() { return sku; }
    public int quantity() { return quantity; }
    public Money unitPrice() { return unitPrice; }
    public UUID designSnapshotId() { return designSnapshotId; }

    /** Unmodifiable: holds are attached and cleared through this class's own methods. */
    public List<UUID> reservationIds() {
        return java.util.Collections.unmodifiableList(reservationIds);
    }

    /** Package-private: only {@link Order#closeRemainder} closes units. */
    void closeUnits(int units) {
        if (units <= 0 || cancelledQuantity + units > quantity) {
            throw new IllegalArgumentException("Cannot close %d more of line %s".formatted(units, id));
        }
        cancelledQuantity += units;
    }

    public int cancelledQuantity() {
        return cancelledQuantity;
    }

    /** Null when the customer asked for no date. */
    public LocalDate requestedDeliveryDate() {
        return requestedDeliveryDate;
    }

    /**
     * Records the date the customer asked for; only a print line is produced to a date. Used when the line is
     * built (placing the order, rehydration) - there is no way to change it on a placed order.
     */
    public OrderLine withRequestedDeliveryDate(LocalDate date) {
        if (date != null && designSnapshotId == null && cartons == null) {
            throw new IllegalArgumentException("Only a print line, or a line of the customer's own SKU in cartons, has a "
                    + "requested delivery date");
        }
        this.requestedDeliveryDate = date;
        return this;
    }

    /**
     * How the line was ordered: {@code cartons} of {@code unitsPerCarton} pieces in the SKU's carton unit (4B-2). Set
     * once, when the order is placed or read back; {@link #quantity()} must be their product.
     */
    public OrderLine withCartons(UUID uomConversionId, int cartons, int unitsPerCarton) {
        java.util.Objects.requireNonNull(uomConversionId, "uomConversionId");
        if (cartons < 1 || unitsPerCarton < 2 || (long) cartons * unitsPerCarton != quantity) {
            throw new IllegalArgumentException("%d carton(s) of %d pieces is not the line's %d pieces".formatted(cartons,
                    unitsPerCarton, quantity));
        }
        if (this.cartons != null && (!this.uomConversionId.equals(uomConversionId) || this.cartons != cartons
                || this.unitsPerCarton != unitsPerCarton)) {
            throw new IllegalStateException("An order line's cartons are fixed when it is placed");
        }
        this.uomConversionId = uomConversionId;
        this.cartons = cartons;
        this.unitsPerCarton = unitsPerCarton;
        return this;
    }

    /**
     * The VAT rate of the line, fixed when the order is placed; its tax is computed from the line's total. Set once.
     */
    public OrderLine withTax(java.math.BigDecimal ratePercent) {
        return withTax(ratePercent, Vat.of(lineTotal(), ratePercent));
    }

    /** The rate and the tax as the line keeps them (read back from its row). */
    public OrderLine withTax(java.math.BigDecimal ratePercent, Money tax) {
        java.util.Objects.requireNonNull(tax, "tax");
        java.math.BigDecimal rate = Vat.rate(ratePercent);
        if (tax.isNegative() || !tax.currency().equals(unitPrice.currency())) {
            throw new IllegalArgumentException("A line's tax is not negative and in the line's currency");
        }
        if (this.taxRate != null && (this.taxRate.compareTo(rate) != 0 || !this.taxAmount.equals(tax))) {
            throw new IllegalStateException("An order line's VAT is fixed when it is placed");
        }
        this.taxRate = rate;
        this.taxAmount = tax;
        return this;
    }

    /** The VAT rate fixed on the line; null for a line without VAT. */
    public java.math.BigDecimal taxRate() { return taxRate; }
    /** The line's VAT; null for a line without VAT. */
    public Money taxAmount() { return taxAmount; }

    /** The line's total with its VAT. */
    public Money grossTotal() {
        return taxAmount == null ? lineTotal() : lineTotal().plus(taxAmount);
    }

    /**
     * What the first {@code units} units of the line are worth with their VAT: the line's total with tax in proportion,
     * rounded so that the whole line adds up to {@link #grossTotal()} exactly - money billed or closed a few units at a
     * time never goes past the line. Without VAT, simply the units at their price.
     */
    public Money grossOf(int units) {
        if (units < 0 || units > quantity) {
            throw new IllegalArgumentException("Between 0 and the line's " + quantity + " units, not " + units);
        }
        if (taxAmount == null) {
            return unitPrice.times(units);
        }
        if (units == quantity) {
            return grossTotal();
        }
        int digits = unitPrice.currency().getDefaultFractionDigits();
        return new Money(grossTotal().amount().multiply(java.math.BigDecimal.valueOf(units))
                .divide(java.math.BigDecimal.valueOf(quantity), digits, java.math.RoundingMode.HALF_UP),
                unitPrice.currency());
    }

    /** What units {@code from} (exclusive) to {@code to} (inclusive) of the line are worth with VAT. */
    public Money grossBetween(int from, int to) {
        return grossOf(to).minus(grossOf(from));
    }

    /** The SKU's carton unit the line was ordered in; null for a line not ordered in cartons. */
    public UUID uomConversionId() { return uomConversionId; }
    /** Cartons ordered; null for a line not ordered in cartons. */
    public Integer cartons() { return cartons; }
    /** Pieces per carton fixed on the line; null for a line not ordered in cartons. */
    public Integer unitsPerCarton() { return unitsPerCarton; }

    /**
     * A line carrying a design snapshot: a print line placed before 4B-2, whose goods are received from the producer
     * before the order is released ({@code Order.hasPrintLines}). A line of the customer's own SKU is not one - its
     * goods are stock - though it is printed to the customer's design too ({@link #printedToCustomerDesign()}).
     */
    public boolean isMadeToOrder() {
        return designSnapshotId != null;
    }

    /**
     * Whether the goods are printed to the customer's design (kltn-docs 17 §4.10, BR-RMA-002): a print line, or a line
     * of the customer's own SKU in cartons (4B-2) - every SKU is a customer's own design. Such goods come back only for a
     * named defect, and a closure after printing started may keep an agreed fee for them.
     */
    public boolean printedToCustomerDesign() {
        return designSnapshotId != null || cartons != null;
    }
}
