package com.stockflow.customer.internal.domain;

import java.util.regex.Pattern;

/**
 * A Vietnamese tax identifier, the identity of a B2B customer (kltn-docs 18 BR-01).
 *
 * <p>Optional on a customer: household businesses and small shops are customers too and may have none
 * yet (decision 2026-10-12). When one is given it is either
 * <ul>
 *   <li>an organisation's tax code: 10 digits, or 10-3 for a dependent unit (Thông tư 86/2024/TT-BTC
 *       art. 5) - the company rule, unchanged; or</li>
 *   <li>a household business's: since 01/07/2025 the representative's 12-digit personal identification
 *       number replaces the tax code of households, household businesses and individuals (Thông tư
 *       86/2024/TT-BTC art. 5(5) and art. 38(2); 12 digits by Luật Căn cước 26/2023/QH15 art. 12(1)). This
 *       settled question T1 from the official texts rather than by a business choice.</li>
 * </ul>
 * The system keeps no customer type, so the 12-digit form is accepted for any customer; an organisation
 * never has one, and such a value is a typing error Sales corrects, like a wrong digit.</p>
 *
 * <p>The canonical form is exactly 10, 10-3 or 12 digits, so "0123456789001", "0123 456 789-001" and
 * "0123456789-001" are one customer and the unique index can compare plain strings. No check digit is
 * verified: the rules for it are not in the documents this system follows, and refusing a real code
 * would be worse than accepting a mistyped one. The same shape is {@code ck_customer_tax_code}
 * (V20261017000540).</p>
 */
public final class TaxCode {

    private static final Pattern CANONICAL = Pattern.compile("\\d{10}(-\\d{3})?|\\d{12}");

    private TaxCode() {
    }

    /** @throws IllegalArgumentException for anything that is not 10, 10-3 or 12 digits */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("taxCode is required");
        String compact = raw.strip().replaceAll("[\\s.]", "");
        if (compact.matches("\\d{13}")) compact = compact.substring(0, 10) + "-" + compact.substring(10);
        if (!CANONICAL.matcher(compact).matches()) {
            throw new IllegalArgumentException("taxCode must have 10 digits, 10-3 for a dependent unit, or the 12 digits"
                    + " of a household business representative's personal identification number");
        }
        return compact;
    }
}
