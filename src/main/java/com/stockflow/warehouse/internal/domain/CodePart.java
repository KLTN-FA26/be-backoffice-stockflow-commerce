package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One part of a location code: a warehouse prefix, a shelf, bin or area code.
 *
 * <p>Only {@code [A-Z0-9]}, upper-cased on the way in. The restriction is what makes a location code
 * unambiguous: with no {@code -} inside a part, the separators can be counted and split, and two
 * different paths can never assemble into the same string (BR-10). The database holds the same rule
 * ({@code ck_warehouse_prefix}, {@code ck_shelf_code}, {@code ck_bin_code}, {@code ck_area_code}).</p>
 *
 * <p>Upper-casing uses {@link Locale#ROOT}: under a Turkish default locale {@code "i".toUpperCase()}
 * is a dotted {@code İ}, which the pattern would then refuse.</p>
 */
public record CodePart(String value) {

    /** {@code warehouse.prefix VARCHAR(10)}. */
    public static final int PREFIX_MAX_LENGTH = 10;
    /** {@code shelf.code}, {@code bin.code}, {@code area.code}: {@code VARCHAR(20)}. */
    public static final int CODE_MAX_LENGTH = 20;

    private static final Pattern ALLOWED = Pattern.compile("[A-Z0-9]+");

    /** Normalises {@code raw} and checks it; the record itself holds only a checked value. */
    public static CodePart of(String raw, int maxLength) {
        if (raw == null) {
            throw invalid("A code is required");
        }
        String upper = raw.toUpperCase(Locale.ROOT);
        if (!ALLOWED.matcher(upper).matches()) {
            throw invalid("A code may only contain letters A-Z and digits, got '" + raw + "'");
        }
        if (upper.length() > maxLength) {
            throw invalid("A code may be at most %d characters, got '%s'".formatted(maxLength, raw));
        }
        return new CodePart(upper);
    }

    @Override
    public String toString() {
        return value;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
