package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The VAT rate of each product tax class (QD-9; {@code ordering.tax_rate}, team plan 4B-3): STANDARD, REDUCED, EXEMPT.
 * A quote line copies its class's rate with its price (kltn-docs 14 BR-21).
 */
public interface TaxRates {

    /** The class a fee naming no product takes: the shipping fee (D31). */
    String STANDARD = "STANDARD";

    /** Every class and its rate, a percentage. */
    Map<String, BigDecimal> all();
}
