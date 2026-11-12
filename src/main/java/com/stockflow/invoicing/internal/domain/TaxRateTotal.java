package com.stockflow.invoicing.internal.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the lines at one VAT rate come to: an invoice states its amount and tax by rate (NĐ 254/2026/NĐ-CP Điều 10
 * khoản 1 điểm đ). Derived from the lines whenever it is asked for, never stored, so it cannot drift from them.
 */
public record TaxRateTotal(BigDecimal taxRate, BigDecimal amount, BigDecimal taxAmount) {

    /** One total per rate on {@code lines}, lowest rate first. */
    public static List<TaxRateTotal> of(List<EInvoiceLine> lines) {
        Map<BigDecimal, BigDecimal[]> byRate = new TreeMap<>();
        for (EInvoiceLine line : lines) {
            BigDecimal[] sums = byRate.computeIfAbsent(line.taxRate(),
                    rate -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            sums[0] = sums[0].add(line.amount());
            sums[1] = sums[1].add(line.taxAmount());
        }
        List<TaxRateTotal> totals = new ArrayList<>();
        byRate.forEach((rate, sums) -> totals.add(new TaxRateTotal(rate, sums[0], sums[1])));
        return totals;
    }
}
