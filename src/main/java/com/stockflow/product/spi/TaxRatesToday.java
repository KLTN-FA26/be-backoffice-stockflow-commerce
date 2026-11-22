package com.stockflow.product.spi;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The VAT rate in force today for each product tax class - what a quote line drawn up today copies (kltn-docs 14
 * BR-21). Implemented by {@code order}, which keeps the rates and their temporary periods ({@code ordering.tax_rate},
 * {@code ordering.tax_rate_period}); here so the customer SKU list the quote editor reads can carry each SKU's rate
 * (R5-4) without the product module depending on order, which depends on it.
 */
public interface TaxRatesToday {

    /** Each class's rate on today's business day, a percentage, by class name ({@code STANDARD}, ...). */
    Map<String, BigDecimal> today();
}
