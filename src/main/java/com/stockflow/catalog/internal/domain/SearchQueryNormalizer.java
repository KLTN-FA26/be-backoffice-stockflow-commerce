package com.stockflow.catalog.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns free text from the search box into a {@link SearchQuery}.
 *
 * <p>"ban go" must find "bàn gỗ", so the same fold the database applies to the stored title
 * ({@code catalog.immutable_unaccent}) is applied here to the input: decompose, drop the combining
 * marks, map {@code đ} to {@code d} (it has no decomposition). Folding on this side as well means
 * the unit tests pin the behaviour without a database, and input typed with combining marks (a
 * Vietnamese keyboard on macOS sends them decomposed) behaves like precomposed input.</p>
 *
 * <p>Anything that is not a letter or digit becomes a word break. That removes every character
 * {@code tsquery} syntax gives meaning to ({@code & | ! : * ( ) ' \ <}), so a term is always safe
 * to put into a query and a query made only of punctuation is simply empty (an empty page, not an
 * error).</p>
 */
public final class SearchQueryNormalizer {

    /** Longest raw input accepted. Longer is refused rather than cut: a cut query answers a different question. */
    public static final int MAX_LENGTH = 100;

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_LETTER_OR_DIGIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    /**
     * The alphabet of {@code common.domain.Sku} (and {@code ck_variants_sku}) in lower case: letters, digits, {@code .},
     * {@code _} and {@code -}, at most 64, not starting with a separator. A key outside it can never equal a stored SKU.
     */
    private static final Pattern SKU_SHAPE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    private SearchQueryNormalizer() {
    }

    /**
     * @param raw what the caller sent; null and blank mean "no search text"
     * @return the folded query, {@link SearchQuery#NONE} for null/blank/punctuation only
     * @throws BusinessException {@code VALIDATION_FAILED} (field {@code q}) above {@value #MAX_LENGTH}
     *                           characters. The message never repeats the input.
     */
    public static SearchQuery normalize(String raw) {
        if (raw == null) {
            return SearchQuery.NONE;
        }
        String trimmed = raw.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_LENGTH) {
            throw tooLong();
        }
        String folded = fold(trimmed);
        String text = NOT_LETTER_OR_DIGIT.matcher(folded).replaceAll(" ").strip();
        if (text.isEmpty()) {
            return SearchQuery.NONE;
        }
        List<String> terms = List.of(text.split(" "));
        return new SearchQuery(text, terms, skuKeys(folded, terms));
    }

    /** Lower case, accent-folded; the same result as {@code lower(immutable_unaccent(x))} for Vietnamese. */
    public static String fold(String value) {
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        String stripped = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        return stripped.replace('đ', 'd').replace('Đ', 'D').toLowerCase(Locale.ROOT);
    }

    /**
     * SKU codes keep their hyphens ({@code SOFA-3S-GREY}), so "sofa-3s-grey" and "sofa 3s grey" both
     * have to be tried. Whitespace-only differences are the only fuzziness allowed here. The input as typed (its
     * whitespace turned into hyphens) is tried first and keeps its {@code _} and {@code .}: {@code CUP_A-RED} is
     * looked up as {@code cup_a-red}. The words joined by hyphens come second, for input typed with spaces.
     */
    private static List<String> skuKeys(String folded, List<String> terms) {
        Set<String> keys = new LinkedHashSet<>();
        String compact = folded.strip().replaceAll("\\s+", "-");
        if (SKU_SHAPE.matcher(compact).matches()) {
            keys.add(compact);
        }
        String joined = String.join("-", terms);
        if (SKU_SHAPE.matcher(joined).matches()) {
            keys.add(joined);
        }
        return new ArrayList<>(keys);
    }

    private static BusinessException tooLong() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED,
                "Search text must be at most " + MAX_LENGTH + " characters",
                List.of(new FieldError("q", "must be at most " + MAX_LENGTH + " characters", null)));
    }
}
