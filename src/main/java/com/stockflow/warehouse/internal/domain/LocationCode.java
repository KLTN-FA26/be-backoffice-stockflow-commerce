package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.regex.Pattern;

/**
 * The global code of a storage location (BR-10): {@code prefix-shelf-level-bin} for a bin, e.g.
 * {@code HN-A01-2-03}, and {@code prefix-area} for a storage area, e.g. {@code HN-RCV01}. It is
 * what a scanner reads, what {@code inventory} stores, and {@code warehouse.storage_location}'s
 * unique key.
 *
 * <p>Assembled once, when the bin or area is created, and never changed (BR-13): every part is
 * immutable, so the stored string can never drift from the path it names.</p>
 *
 * <p>The two patterns are the ones in {@code ck_storage_location_code}, and the level is written
 * without a leading zero because that is how {@code tg_bin_location} rebuilds the code to compare -
 * {@code HCM-A01-02-B} would be refused by the database. Building the code here rather than in SQL
 * gives the user a {@code 400} with a reason instead of a constraint violation.</p>
 */
public record LocationCode(String value) {

    /** {@code storage_location.location_code VARCHAR(64)}. */
    public static final int MAX_LENGTH = 64;
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 99;

    private static final Pattern BIN = Pattern.compile(
            "[A-Z0-9]{1,10}-[A-Z0-9]{1,20}-[1-9][0-9]?-[A-Z0-9]{1,20}");
    private static final Pattern AREA = Pattern.compile("[A-Z0-9]{1,10}-[A-Z0-9]{1,20}");

    /** Accepts an existing code exactly as stored; it must already be upper case. */
    public LocationCode {
        if (value == null || value.length() > MAX_LENGTH
                || !(BIN.matcher(value).matches() || AREA.matcher(value).matches())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "'%s' is not a location code".formatted(value));
        }
    }

    public static LocationCode ofBin(String prefix, String shelfCode, int levelIndex, String binCode) {
        if (levelIndex < MIN_LEVEL || levelIndex > MAX_LEVEL) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A level index is %d-%d, got %d".formatted(MIN_LEVEL, MAX_LEVEL, levelIndex));
        }
        return new LocationCode(String.join("-",
                CodePart.of(prefix, CodePart.PREFIX_MAX_LENGTH).value(),
                CodePart.of(shelfCode, CodePart.CODE_MAX_LENGTH).value(),
                Integer.toString(levelIndex),
                CodePart.of(binCode, CodePart.CODE_MAX_LENGTH).value()));
    }

    public static LocationCode ofArea(String prefix, String areaCode) {
        return new LocationCode(String.join("-",
                CodePart.of(prefix, CodePart.PREFIX_MAX_LENGTH).value(),
                CodePart.of(areaCode, CodePart.CODE_MAX_LENGTH).value()));
    }

    /** Told apart by shape alone: a bin code has four parts, an area code two. */
    public StorageLocationKind kind() {
        return BIN.matcher(value).matches() ? StorageLocationKind.BIN : StorageLocationKind.AREA;
    }

    @Override
    public String toString() {
        return value;
    }
}
