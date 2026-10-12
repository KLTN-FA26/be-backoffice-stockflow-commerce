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
 * One quoted line: a blank, the confirmed design printed on it, how it is printed, and its price breaks.
 * The lowest break is the minimum order quantity (BR-ORD-007). What the customer accepted — a quantity at
 * or above the MOQ and the price of its break — is fixed on the line at acceptance and never recomputed:
 * the order is placed at that price even if the tiers were read differently later.
 */
public final class QuoteLine {

    /** {@code print_spec VARCHAR(2000)}. */
    public static final int MAX_PRINT_SPEC = 2000;
    /** Keeps line totals inside {@code NUMERIC(19, 4)} order amounts with room to spare. */
    private static final int MAX_QUANTITY = 10_000_000;

    private final UUID id;
    private final int lineNo;
    private final Sku blankSku;
    private final UUID designSnapshotId;
    private final String printSpec;
    private final int quantity;
    private final List<PriceTier> tiers;
    private Integer acceptedQuantity;
    private Money acceptedUnitPrice;

    public QuoteLine(UUID id, int lineNo, Sku blankSku, UUID designSnapshotId, String printSpec, int quantity,
                     List<PriceTier> tiers, Integer acceptedQuantity, Money acceptedUnitPrice) {
        this.id = Objects.requireNonNull(id, "id");
        if (lineNo < 1) {
            throw new IllegalArgumentException("Line numbers start at 1");
        }
        this.lineNo = lineNo;
        this.blankSku = Objects.requireNonNull(blankSku, "blankSku");
        this.designSnapshotId = Objects.requireNonNull(designSnapshotId, "designSnapshotId");
        String spec = printSpec == null || printSpec.isBlank() ? null : printSpec.strip();
        if (spec != null && spec.length() > MAX_PRINT_SPEC) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A print specification is at most " + MAX_PRINT_SPEC + " characters");
        }
        this.printSpec = spec;
        if (tiers == null || tiers.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Line " + lineNo + " needs at least one price tier");
        }
        var seen = new HashSet<Integer>();
        for (PriceTier tier : tiers) {
            if (!seen.add(tier.minQuantity())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Line " + lineNo + " has two price tiers starting at " + tier.minQuantity());
            }
        }
        this.tiers = tiers.stream().sorted(Comparator.comparingInt(PriceTier::minQuantity)).toList();
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Line " + lineNo + " quantity is out of range");
        }
        if (quantity < moq()) {
            throw new BusinessException(ErrorCode.QUOTE_BELOW_MOQ,
                    "Line %d proposes %d units, below its MOQ of %d".formatted(lineNo, quantity, moq()));
        }
        this.quantity = quantity;
        if ((acceptedQuantity == null) != (acceptedUnitPrice == null)) {
            throw new IllegalArgumentException("An accepted line has both its quantity and its price");
        }
        this.acceptedQuantity = acceptedQuantity;
        this.acceptedUnitPrice = acceptedUnitPrice;
    }

    /** The minimum order quantity: where the lowest price break starts. */
    public int moq() {
        return tiers.getFirst().minQuantity();
    }

    /**
     * The unit price of {@code units}: the break with the highest start not above it. Server-side only;
     * a price sent by a client is never used.
     */
    public Money priceFor(int units) {
        if (units < moq() || units > MAX_QUANTITY) {
            throw new BusinessException(ErrorCode.QUOTE_BELOW_MOQ,
                    "Line %d: %d units is below its MOQ of %d".formatted(lineNo, units, moq()));
        }
        Money price = tiers.getFirst().unitPrice();
        for (PriceTier tier : tiers) {
            if (tier.minQuantity() <= units) {
                price = tier.unitPrice();
            }
        }
        return price;
    }

    void accept(int units) {
        Money price = priceFor(units);
        acceptedQuantity = units;
        acceptedUnitPrice = price;
    }

    void clearAcceptance() {
        acceptedQuantity = null;
        acceptedUnitPrice = null;
    }

    public UUID id() { return id; }
    public int lineNo() { return lineNo; }
    public Sku blankSku() { return blankSku; }
    public UUID designSnapshotId() { return designSnapshotId; }
    public String printSpec() { return printSpec; }
    public int quantity() { return quantity; }
    public List<PriceTier> tiers() { return tiers; }
    public Integer acceptedQuantity() { return acceptedQuantity; }
    public Money acceptedUnitPrice() { return acceptedUnitPrice; }
}
