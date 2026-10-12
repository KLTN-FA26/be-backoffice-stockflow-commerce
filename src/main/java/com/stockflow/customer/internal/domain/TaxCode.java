package com.stockflow.customer.internal.domain;

import java.util.regex.Pattern;

/**
 * A Vietnamese business tax code, the identity of a B2B customer (kltn-docs 18 BR-01).
 *
 * <p>The customers are enterprises that contacted Sales first (kltn-docs 18, introduction). The Law on
 * Tax Administration 38/2019/QH14 art. 30(2) and Circular 86/2024/TT-BTC art. 5 (in force since 2025-02-06, replacing 105/2020) give
 * an enterprise or other legal entity 10 digits and a dependent unit those 10, a hyphen and 3 more -
 * as quoted by secondary legal sources; the official text was not retrieved. Individuals and household
 * businesses use the 12-digit personal identification number since 2025-07-01 and are refused here:
 * whether household businesses are customers is the business owner's decision (see the SCRUM-429
 * handoff). The canonical form is exactly 10 or 10-3, so
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
