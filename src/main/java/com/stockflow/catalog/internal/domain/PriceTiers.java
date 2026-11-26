package com.stockflow.catalog.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * The rules every list of price breaks of the catalogue follows - a customer's own price ({@link CustomerPrice}) and a
 * variant's standard price ({@link StandardPrice}) alike: 1 to {@value #MAX} breaks, no two starting at the same number
 * of cartons, kept in the order they start. The same invariants are CHECKs and unique keys on their tier tables.
 */
final class PriceTiers {

    /** Generous for a price list, small enough that a mistyped request cannot store thousands of rows. */
    static final int MAX = 20;

    private PriceTiers() {
    }

    /**
     * The breaks sorted by where they start.
     *
     * @param what names the price in a refusal ("A customer price", "A standard price")
     * @throws BusinessException {@code VALIDATION_FAILED}
     */
    static <T> List<T> sorted(List<T> tiers, ToIntFunction<T> start, String what) {
        if (tiers == null || tiers.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, what + " needs at least one tier");
        }
        if (tiers.size() > MAX) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "At most " + MAX + " price tiers");
        }
        var starts = new HashSet<Integer>();
        for (T tier : tiers) {
            if (!starts.add(start.applyAsInt(Objects.requireNonNull(tier, "tier")))) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Two price tiers start at " + start.applyAsInt(tier));
            }
        }
        var sorted = new ArrayList<>(tiers);
        sorted.sort(Comparator.comparingInt(start));
        return List.copyOf(sorted);
    }

    /** The note kept with a price: blank is none, at most 500 characters. */
    static String note(String note) {
        if (note == null || note.isBlank()) return null;
        if (note.length() > 500) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "note is too long");
        return note.strip();
    }
}
