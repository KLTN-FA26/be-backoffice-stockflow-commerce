package com.stockflow.order.internal.domain;

import com.stockflow.common.domain.Money;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One quoted line: cartons of one of the customer's own SKUs, with its price breaks (kltn-docs 14 BR-04, BR-07,
 * BR-08; team plan 4B-2).
 *
 * <p><b>Ordered by the carton, priced by the piece.</b> The line counts whole cartons; each carton holds
 * {@link #unitsPerCarton()} pieces - the factor of the SKU's carton unit when the line was written, fixed on the line,
 * so a later carton spec does not move a quote already made (14 BR-08, 01 BR-10). Price breaks start at a number of
 * cartons and price one piece; the line's total is cartons x pieces per carton x price. The lowest break is the
 * minimum order in cartons (BR-07). Breaks are read at the quote's total cartons of the SKU; a SKU is on a quote once
 * (BR-22), so that is the line's own cartons.</p>
 *
 * <p><b>Lines drafted before 4B-2</b> name a blank, the customer's design printed on it and a number of units
 * ({@link Measure#UNIT}); their breaks start at units. They are kept as they were, to read (QD-16): such a line counts
 * one piece per "carton", so every total below is right for it too, and {@link Quote} refuses to send, accept or
 * convert a quote holding one. Sales replaces its lines with the customer's own SKUs.</p>
 *
 * <p>What the customer accepted - a number at or above the MOQ and the price of its break - is fixed on the line at
 * acceptance and never recomputed: the order is placed at that price even if the breaks were read differently
 * later.</p>
 */
public final class QuoteLine {

    /** {@code print_spec VARCHAR(2000)}, on a line drafted before 4B-2. */
    public static final int MAX_PRINT_SPEC = 2000;
    /** Pieces on one line: keeps line totals inside {@code NUMERIC(19, 4)} order amounts ({@code ck_quote_line_cartons}). */
    public static final int MAX_UNITS = 10_000_000;

    /** What a line counts. */
    public enum Measure {
        /** Whole cartons of the customer's own SKU: every line Sales writes now. */
        CARTON,
        /** Units of a blank printed with a design: a line drafted before 4B-2, read only. */
        UNIT
    }

    private final UUID id;
    private final int lineNo;
    private final Measure measure;
    private final Sku sku;
    private final UUID uomConversionId;
    private final int unitsPerCarton;
    private final UUID designSnapshotId;
    private final String printSpec;
    private final int quantity;
    private final List<PriceTier> tiers;
    private final String taxClass;
    private final java.math.BigDecimal taxRate;
    private Integer acceptedQuantity;
    private Money acceptedUnitPrice;

    private QuoteLine(UUID id, int lineNo, Measure measure, Sku sku, UUID uomConversionId, int unitsPerCarton,
                      UUID designSnapshotId, String printSpec, int quantity, List<PriceTier> tiers,
                      Integer acceptedQuantity, Money acceptedUnitPrice, String taxClass,
                      java.math.BigDecimal taxRate) {
        this.id = Objects.requireNonNull(id, "id");
        if (lineNo < 1) {
            throw new IllegalArgumentException("Line numbers start at 1");
        }
        this.lineNo = lineNo;
        this.measure = Objects.requireNonNull(measure, "measure");
        this.sku = Objects.requireNonNull(sku, "sku");
        this.uomConversionId = uomConversionId;
        this.unitsPerCarton = unitsPerCarton;
        this.designSnapshotId = designSnapshotId;
        this.printSpec = printSpec;
        if (quantity < 1 || (long) quantity * unitsPerCarton > MAX_UNITS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Line %d: %d %s is out of range (at most %d pieces)"
                    .formatted(lineNo, quantity, noun(), MAX_UNITS));
        }
        this.quantity = quantity;
        // No tiers = not priced yet. Only a customer's reorder made one (user decision R1), and the reorder went with team
        // plan 5-2; drafts it left are still read back, and the quote refuses to be sent - so accepted or converted -
        // until Sales has priced every line.
        tiers = tiers == null ? List.of() : tiers;
        var seen = new HashSet<Integer>();
        for (PriceTier tier : tiers) {
            if (!seen.add(tier.minQuantity())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Line %d has two price tiers starting at %d %s".formatted(lineNo, tier.minQuantity(), noun()));
            }
        }
        this.tiers = tiers.stream().sorted(Comparator.comparingInt(PriceTier::minQuantity)).toList();
        if ((acceptedQuantity == null) != (acceptedUnitPrice == null)) {
            throw new IllegalArgumentException("An accepted line has both its quantity and its price");
        }
        this.acceptedQuantity = acceptedQuantity;
        this.acceptedUnitPrice = acceptedUnitPrice;
        if ((taxClass == null) != (taxRate == null) || (taxClass != null && measure == Measure.UNIT)) {
            throw new IllegalArgumentException("A carton line has its tax class and rate, or neither; a legacy line none");
        }
        this.taxClass = taxClass;
        this.taxRate = taxRate == null ? null : Vat.rate(taxRate);
    }

    /** A carton line without its VAT rate yet (its product has no tax class): as {@link #carton(UUID, int, Sku,
     *  UUID, int, int, List, Integer, Money, String, java.math.BigDecimal)} with none. */
    public static QuoteLine carton(UUID id, int lineNo, Sku sku, UUID uomConversionId, int unitsPerCarton, int cartons,
                                   List<PriceTier> tiers, Integer acceptedCartons, Money acceptedUnitPrice) {
        return carton(id, lineNo, sku, uomConversionId, unitsPerCarton, cartons, tiers, acceptedCartons,
                acceptedUnitPrice, null, null);
    }

    /**
     * A line of cartons of the customer's own SKU.
     *
     * @param uomConversionId the SKU's carton unit
     * @param unitsPerCarton  its factor now, fixed on the line; a carton holds more than one piece (01 BR-03)
     * @param taxClass        its product's tax class, copied with the price (14 BR-21); null while the product has
     *                        none - the quote is then not sent
     * @param taxRate         that class's VAT rate, a percentage
     */
    public static QuoteLine carton(UUID id, int lineNo, Sku sku, UUID uomConversionId, int unitsPerCarton, int cartons,
                                   List<PriceTier> tiers, Integer acceptedCartons, Money acceptedUnitPrice,
                                   String taxClass, java.math.BigDecimal taxRate) {
        Objects.requireNonNull(uomConversionId, "uomConversionId");
        if (unitsPerCarton < 2) {
            throw new BusinessException(ErrorCode.CARTON_SPEC_MISSING,
                    "%s: a carton holds more than one piece, not %d".formatted(sku.code(), unitsPerCarton));
        }
        return new QuoteLine(id, lineNo, Measure.CARTON, sku, uomConversionId, unitsPerCarton, null, null, cartons,
                tiers, acceptedCartons, acceptedUnitPrice, taxClass, taxRate);
    }

    /** A line drafted before 4B-2, as its row keeps it: a blank, a design, units (QD-16). */
    public static QuoteLine legacy(UUID id, int lineNo, Sku blankSku, UUID designSnapshotId, String printSpec,
                                   int units, List<PriceTier> tiers, Integer acceptedUnits, Money acceptedUnitPrice) {
        Objects.requireNonNull(designSnapshotId, "designSnapshotId");
        String spec = printSpec == null || printSpec.isBlank() ? null : printSpec.strip();
        if (spec != null && spec.length() > MAX_PRINT_SPEC) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A print specification is at most " + MAX_PRINT_SPEC + " characters");
        }
        return new QuoteLine(id, lineNo, Measure.UNIT, blankSku, null, 1, designSnapshotId, spec, units, tiers,
                acceptedUnits, acceptedUnitPrice, null, null);
    }

    /** Whether the line was drafted before 4B-2 on a blank and a design: kept to read (QD-16). */
    public boolean legacy() {
        return measure == Measure.UNIT;
    }

    /** Whether Sales has priced the line; a reorder drafted before team plan 5-2 may have started without (R1). */
    public boolean priced() {
        return !tiers.isEmpty();
    }

    /** The minimum order, in the line's measure: where the lowest price break starts. */
    public int moq() {
        requirePriced();
        return tiers.getFirst().minQuantity();
    }

    /**
     * The price of one piece at {@code quantity} (cartons; units on a legacy line): the break with the highest start
     * not above it. Server-side only; a price sent by a client is never used.
     */
    public Money priceFor(int quantity) {
        requirePriced();
        if ((long) quantity * unitsPerCarton > MAX_UNITS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Line %d: %d %s is more than %d pieces"
                    .formatted(lineNo, quantity, noun(), MAX_UNITS));
        }
        if (quantity < moq()) {
            throw new BusinessException(ErrorCode.QUOTE_BELOW_MOQ,
                    "Line %d: %d %s is below its MOQ of %d".formatted(lineNo, quantity, noun(), moq()));
        }
        Money price = tiers.getFirst().unitPrice();
        for (PriceTier tier : tiers) {
            if (tier.minQuantity() <= quantity) {
                price = tier.unitPrice();
            }
        }
        return price;
    }

    /** {@code quantity} (cartons; units on a legacy line) at {@code unitPrice} a piece. */
    public Money total(int quantity, Money unitPrice) {
        return unitPrice.times(Math.multiplyExact(quantity, unitsPerCarton));
    }

    /** @param skuQuantity the quote's accepted total of this line's SKU, which picks the break */
    void accept(int quantity, int skuQuantity) {
        Money price = priceFor(skuQuantity);
        acceptedQuantity = quantity;
        acceptedUnitPrice = price;
    }

    private void requirePriced() {
        if (tiers.isEmpty()) {
            throw new BusinessException(ErrorCode.QUOTE_NOT_PRICED, "Line " + lineNo + " has no price yet");
        }
    }

    void clearAcceptance() {
        acceptedQuantity = null;
        acceptedUnitPrice = null;
    }

    /** "cartons", or "units" on a legacy line: how messages name the line's measure. */
    public String noun() {
        return measure == Measure.CARTON ? "cartons" : "units";
    }

    public UUID id() { return id; }
    public int lineNo() { return lineNo; }
    public Measure measure() { return measure; }
    /** The customer's own SKU; on a legacy line, the blank. */
    public Sku sku() { return sku; }
    /** The SKU's carton unit; null on a legacy line. */
    public UUID uomConversionId() { return uomConversionId; }
    /** Pieces per carton fixed on the line; 1 on a legacy line. */
    public int unitsPerCarton() { return unitsPerCarton; }
    /** The design printed on the blank, on a legacy line; null on a carton line, whose SKU is its design. */
    public UUID designSnapshotId() { return designSnapshotId; }
    /** How the blank is printed, on a legacy line; null on a carton line. */
    public String printSpec() { return printSpec; }
    /** Cartons proposed; units on a legacy line. */
    public int quantity() { return quantity; }
    /** Pieces proposed. */
    public int units() { return quantity * unitsPerCarton; }
    public List<PriceTier> tiers() { return tiers; }
    /** Cartons accepted (units on a legacy line); null until accepted. */
    public Integer acceptedQuantity() { return acceptedQuantity; }
    /** Pieces accepted; null until accepted. */
    public Integer acceptedUnits() { return acceptedQuantity == null ? null : acceptedQuantity * unitsPerCarton; }
    /** The price of one piece the customer accepted; null until accepted. */
    public Money acceptedUnitPrice() { return acceptedUnitPrice; }
    /** Its product's tax class, copied with the price (4B-3); null while the product has none, and on a legacy line. */
    public String taxClass() { return taxClass; }
    /** The VAT rate of {@link #taxClass()}, a percentage; null with it. */
    public java.math.BigDecimal taxRate() { return taxRate; }

    /** The VAT of {@code net}, the line's total at some price; null while the line has no rate. */
    public Money taxOf(Money net) {
        return taxRate == null ? null : Vat.of(net, taxRate);
    }
}
