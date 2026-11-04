package com.stockflow.product.internal.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The key two product names are compared by when looking for a possible duplicate (SCRUM-88): lower case,
 * without Vietnamese diacritics, and without anything that is not a letter or a digit.
 *
 * <p>Spaces and punctuation go too, not only their repetitions, because the same cup is typed
 * "Ly giấy 12oz" by one person and "Ly giấy 12 oz" by the next. {@code đ} has no Unicode decomposition,
 * so it is replaced before the marks are dropped.</p>
 *
 * <p>Computed here rather than in SQL: the unaccent function the catalog search uses lives in the
 * {@code catalog} schema, and the product module does not call into another module's schema.</p>
 */
public final class ProductNameKey {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");

    private ProductNameKey() {
    }

    /** The key of {@code name}; empty for a name with no letter or digit, or for none. */
    public static String of(String name) {
        if (name == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(name.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD);
        String unmarked = MARKS.matcher(decomposed).replaceAll("");
        return NOT_LETTER_OR_DIGIT.matcher(unmarked.toLowerCase(Locale.ROOT)).replaceAll("");
    }
}
