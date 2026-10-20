package com.stockflow.procurement.internal.domain;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * The one rule for what may be a purchase price, shared by the catalog and by purchase order lines:
 * a line priced by hand and a line priced from the catalog must be held to the same standard, or the
 * same number is acceptable in one place and refused in the other.
 *
 * <p>The decimals are capped at two whatever the currency's own scale is, because the column is
 * {@code numeric(18,2)}: a three-decimal currency such as KWD would otherwise be rounded by the
 * database without a word.</p>
 */
public final class PurchasePrices {

    /** The largest amount {@code numeric(18,2)} holds. */
    public static final BigDecimal MAX = new BigDecimal("9999999999999999.99");

    private static final int COLUMN_SCALE = 2;

    private PurchasePrices() {}

    /** Checked as entered, before any rounding to the currency's scale could hide an excess decimal. */
    public static boolean acceptable(BigDecimal amount, Currency currency) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX) > 0) return false;
        return amount.stripTrailingZeros().scale() <= maxScale(currency);
    }

    /** The decimals a price in {@code currency} may carry: the currency's own, at most the column's two. */
    public static int maxScale(Currency currency) {
        return Math.min(COLUMN_SCALE, currency.getDefaultFractionDigits());
    }
}
