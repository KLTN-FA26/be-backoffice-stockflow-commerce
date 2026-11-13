package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    /**
     * Every class and the rate in force for it on {@code day} (WF-05 E2): the rate of a period covering the day
     * ({@code ordering.tax_rate_period}, a temporary reduction), else the class's own. What an e-invoice of goods
     * delivered that day carries.
     */
    Map<String, BigDecimal> onDay(LocalDate day);
}
