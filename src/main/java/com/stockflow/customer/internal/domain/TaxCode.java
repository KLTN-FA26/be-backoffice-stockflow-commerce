package com.stockflow.customer.internal.domain;

import java.util.regex.Pattern;

/**
 * A Vietnamese business tax code, the identity of a B2B customer (kltn-docs 18 BR-01).
 *
 * <p>Optional on a customer: household businesses and small shops are customers too and may have none
 * yet (decision 2026-10-12). When one is given it follows the company's existing rule, unchanged by that
 * decision: 10 digits for an enterprise, 10-3 for a dependent unit (Law on Tax Administration
 * 38/2019/QH14 art. 30(2), Circular 86/2024/TT-BTC art. 5, as quoted by secondary sources; the official
 * text was not retrieved). A 12-digit personal identification number is refused - whether to accept it
 * is open question T1 in docs/handoffs/SPRINT2-PRICING-PLAN.md. The canonical form is exactly 10 or 10-3, so
 * "0123456789001", "0123 456 789-001" and "0123456789-001" are one customer and the unique index can
 * compare plain strings. The check digit is not verified: the rule for it is not in the documents
 * this system follows, and refusing a real code would be worse than accepting a mistyped one that
 * Sales can correct. The same shape is {@code ck_customer_tax_code}.</p>
 */
public final class TaxCode {

    private static final Pattern CANONICAL = Pattern.compile("\\d{10}(-\\d{3})?");

    private TaxCode() {
    }

    /** @throws IllegalArgumentException for anything that is not 10 or 13 digits */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("taxCode is required");
        String compact = raw.strip().replaceAll("[\\s.]", "");
        if (compact.matches("\\d{13}")) compact = compact.substring(0, 10) + "-" + compact.substring(10);
        if (!CANONICAL.matcher(compact).matches()) {
            throw new IllegalArgumentException("taxCode must have 10 digits, or 10-3 for a dependent unit");
        }
        return compact;
    }
}
