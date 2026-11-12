package com.stockflow.invoicing.internal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

/**
 * One line of an e-invoice (plan §1.4; NĐ 254 Điều 10 khoản 1 điểm đ: name, unit, quantity, unit price, amount before
 * tax, rate, tax). Money is in whole dong.
 *
 * <p>The amount is the quantity times the unit price, and the VAT is the amount at the rate rounded half up to the
 * dong, line by line (kltn-docs 14 BR-21): an invoice's tax is the sum of its lines' taxes, never the tax of its sum.
 * Both are computed here and never taken from a caller, so a line cannot disagree with itself; the same arithmetic is
 * {@code ck_einvoice_line_amount}.</p>
 *
 * @param feeKind        which fee a FEE line is; null on a goods line
 * @param orderLineId    the order line a goods line delivers; null on a fee line
 * @param sku            the customer's SKU of a goods line, or the SKU a plate fee is for; null on the shipping fee
 * @param quantity       cartons (or pieces, for a line not ordered in cartons); 1 on a fee line. Below zero only on an
 *                       adjustment that reduces (TT 91 Điều 10 khoản 6 điểm c), never on an original
 * @param unitsPerCarton the pieces in a carton, printed with the name; null when the line is not counted in cartons
 */
public record EInvoiceLine(UUID id, int lineNo, Kind kind, FeeKind feeKind, UUID orderLineId, String sku,
                           String itemName, String unit, int quantity, Integer unitsPerCarton, BigDecimal unitPrice,
                           BigDecimal taxRate, BigDecimal amount, BigDecimal taxAmount) {

    /** Goods delivered, or a fee of the order. */
    public enum Kind { GOODS, FEE }

    /** The order's fee lines (kltn-docs 14 BR-17, BR-20), by the names the order module gives them. */
    public enum FeeKind { PLATE_FEE, SHIPPING_FEE }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public EInvoiceLine {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(taxRate, "taxRate");
        if (lineNo < 1) {
            throw new IllegalArgumentException("Lines are numbered from 1");
        }
        if (itemName == null || itemName.isBlank() || unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("A line names what it is and the unit it is counted in");
        }
        itemName = itemName.strip();
        unit = unit.strip();
        if (kind == Kind.GOODS && (feeKind != null || orderLineId == null || sku == null)) {
            throw new IllegalArgumentException("A goods line names its order line and SKU, and no fee");
        }
        if (kind == Kind.FEE && (feeKind == null || orderLineId != null || unitsPerCarton != null)) {
            throw new IllegalArgumentException("A fee line says which fee, and names no order line or carton");
        }
        if (quantity == 0 || (unitsPerCarton != null && unitsPerCarton < 1)) {
            throw new IllegalArgumentException("A line has a quantity, and a carton holds at least one piece");
        }
        if (unitPrice.signum() < 0 || unitPrice.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("A unit price is whole dong and not below zero");
        }
        if (taxRate.signum() < 0 || taxRate.compareTo(HUNDRED) >= 0 || taxRate.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("A VAT rate is 0 to below 100, two decimals at most");
        }
        unitPrice = unitPrice.setScale(0, RoundingMode.UNNECESSARY);
        taxRate = taxRate.setScale(2, RoundingMode.UNNECESSARY);
        BigDecimal expectedAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        BigDecimal expectedTax = vat(expectedAmount, taxRate);
        if (amount == null || taxAmount == null || amount.compareTo(expectedAmount) != 0
                || taxAmount.compareTo(expectedTax) != 0) {
            throw new IllegalArgumentException("Line %d: %s + %s is not %d x %s at %s%%".formatted(lineNo, amount,
                    taxAmount, quantity, unitPrice, taxRate));
        }
        amount = expectedAmount;
        taxAmount = expectedTax;
    }

    /** Goods of one order line: {@code quantity} of {@code unit} at {@code unitPrice} each. */
    public static EInvoiceLine goods(UUID id, int lineNo, UUID orderLineId, String sku, String itemName, String unit,
                                     int quantity, Integer unitsPerCarton, BigDecimal unitPrice, BigDecimal taxRate) {
        BigDecimal price = unitPrice.setScale(0, RoundingMode.UNNECESSARY);
        BigDecimal amount = price.multiply(BigDecimal.valueOf(quantity));
        return new EInvoiceLine(id, lineNo, Kind.GOODS, null, orderLineId, sku, itemName, unit, quantity,
                unitsPerCarton, price, taxRate, amount, vat(amount, taxRate));
    }

    /** A fee, once, for {@code amount}. */
    public static EInvoiceLine fee(UUID id, int lineNo, FeeKind feeKind, String sku, String itemName, String unit,
                                   BigDecimal amount, BigDecimal taxRate) {
        BigDecimal price = amount.setScale(0, RoundingMode.UNNECESSARY);
        return new EInvoiceLine(id, lineNo, Kind.FEE, feeKind, null, sku, itemName, unit, 1, null, price, taxRate,
                price, vat(price, taxRate));
    }

    /** The same line under another name: the one thing the accountant corrects on a draft's line. */
    public EInvoiceLine renamed(String name) {
        return new EInvoiceLine(id, lineNo, kind, feeKind, orderLineId, sku, name, unit, quantity, unitsPerCarton,
                unitPrice, taxRate, amount, taxAmount);
    }

    /** {@code amount} at {@code ratePercent}, rounded half up to the dong (away from zero below it, as the database rounds). */
    public static BigDecimal vat(BigDecimal amount, BigDecimal ratePercent) {
        return amount.multiply(ratePercent).divide(HUNDRED, 0, RoundingMode.HALF_UP);
    }
}
