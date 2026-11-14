package com.stockflow.invoicing.internal.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a buyer's name or address shorter where a rendered invoice has no room for it whole (NĐ 254/2026/NĐ-CP Phụ lục
 * điểm 4.a, as read in plans/E0-legal.md §1 row 9): the common nouns the law lists may be abbreviated - "Phường" to "P",
 * "Thành phố" to "TP", "Việt Nam" to "VN", "Cổ phần" to "CP", "Trách nhiệm hữu hạn" to "TNHH", "Khu công nghiệp" to
 * "KCN", "Sản xuất" to "SX", "Chi nhánh" to "CN" - as long as the house number, street, ward and province are all
 * still there.
 *
 * <p>So this only ever swaps those nouns for their abbreviations; it never cuts text. A value still too long after
 * every abbreviation is returned as long as it is, for the caller to refuse or the provider to reject: dropping the end
 * of an address would drop exactly what the law says must stay.</p>
 *
 * <p>The invoice itself keeps the full text ({@link InvoiceParty}); this is applied when an invoice is rendered or
 * sent, and only when the full text does not fit.</p>
 */
public final class InvoiceAbbreviations {

    /** Longest first, so "Thành phố" is taken before anything shorter could split it. Order kept: results are stable. */
    private static final Map<Pattern, String> RULES = new LinkedHashMap<>();

    static {
        rule("Trách nhiệm hữu hạn", "TNHH");
        rule("Khu công nghiệp", "KCN");
        rule("Thành phố", "TP");
        rule("Chi nhánh", "CN");
        rule("Việt Nam", "VN");
        rule("Sản xuất", "SX");
        rule("Cổ phần", "CP");
        rule("Phường", "P");
    }

    private InvoiceAbbreviations() {
    }

    /**
     * {@code text} as it is when it fits {@code maxLength}; otherwise with the allowed abbreviations applied one after
     * the other until it fits or none is left.
     */
    public static String fit(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        String shorter = text;
        for (Map.Entry<Pattern, String> rule : RULES.entrySet()) {
            shorter = rule.getKey().matcher(shorter).replaceAll(Matcher.quoteReplacement(rule.getValue()));
            if (shorter.length() <= maxLength) {
                break;
            }
        }
        return shorter;
    }

    /** Whole words only, in any letter case: "phường 5" and "PHƯỜNG 5" both, never the middle of another word. */
    private static void rule(String words, String abbreviation) {
        RULES.put(Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(words) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), abbreviation);
    }
}
