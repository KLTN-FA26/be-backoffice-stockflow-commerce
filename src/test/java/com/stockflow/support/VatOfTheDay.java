package com.stockflow.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * The VAT rate a tax class has on a day, as the migrated database says (HĐ-7: {@code ordering.tax_rate} and its
 * periods, V20261201000200 - STANDARD at 8% through 2026, 10% after). What a quote drawn up today and an invoice dated
 * today both carry; a test that fixes an order's rate by hand fixes this one, or the invoice is flagged
 * TAX_RATE_DIFFERS_FROM_CONTRACT. Read, never assumed, so a test means the same in 2026 and in 2027.
 */
public final class VatOfTheDay {

    private VatOfTheDay() {
    }

    /** STANDARD today, Vietnam time. */
    public static BigDecimal standard(JdbcTemplate jdbc) {
        return of(jdbc, "STANDARD", LocalDate.now(ConfirmedOrders.VIETNAM));
    }

    public static BigDecimal of(JdbcTemplate jdbc, String taxClass, LocalDate day) {
        return jdbc.queryForObject("""
                SELECT COALESCE(p.rate, r.rate) FROM ordering.tax_rate r
                  LEFT JOIN ordering.tax_rate_period p
                    ON p.tax_class = r.tax_class AND ? BETWEEN p.valid_from AND p.valid_to
                 WHERE r.tax_class = ?""", BigDecimal.class, java.sql.Date.valueOf(day), taxClass);
    }

    /** {@code amount} x {@code rate} %, to the dong, half up - as a line's VAT is. */
    public static BigDecimal tax(long amount, BigDecimal rate) {
        return BigDecimal.valueOf(amount).multiply(rate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    }
}
