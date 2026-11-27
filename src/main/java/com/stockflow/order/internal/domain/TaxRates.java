package com.stockflow.order.internal.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * The VAT rate of each product tax class (QD-9; {@code ordering.tax_rate}, team plan 4B-3): STANDARD, REDUCED, EXEMPT.
 * A quote line copies with its price the rate its class has on the day the quote is drawn up (kltn-docs 14 BR-21;
 * HĐ-7), and an e-invoice the rate of its own day. There is no "rate of the class" without a day: during a reduction
 * ({@code ordering.tax_rate_period}) a quote that read the class's own rate would promise a rate no invoice carries.
 */
public interface TaxRates {

    /** The class a fee naming no product takes: the shipping fee (D31). */
    String STANDARD = "STANDARD";

    /**
     * Every class and the rate in force for it on {@code day} (WF-05 E2), a percentage: the rate of a period covering
     * the day ({@code ordering.tax_rate_period}, a temporary reduction), else the class's own. What a quote drawn up
     * that day copies, and what an e-invoice of goods delivered that day carries.
     */
    Map<String, BigDecimal> onDay(LocalDate day);
}
