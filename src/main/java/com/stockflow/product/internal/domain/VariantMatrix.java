package com.stockflow.product.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Every combination of one option per axis. Enumerated with the first axis varying slowest, so the
 * first combination is the first option of every axis. It is the default variant's only for the FULL
 * matrix: a restricted generation that leaves that combination out never gives the default a different
 * one (see {@code ProductVariantService}).
 */
public final class VariantMatrix {

    /** One bound per call so a generation request cannot hold a transaction open over thousands of rows. */
    public static final int MAX_PER_CALL = 100;

    private VariantMatrix() {
    }

    /** One option from each axis, in axis order. */
    public record Combination(List<AxisOption> options) {

        public Combination {
            options = List.copyOf(options);
        }
    }

    /**
     * Refuses a matrix that cannot fit in one call, from the axis sizes alone, before anything is
     * enumerated: the product of the sizes is a count, and building 20^6 combinations just to count
     * them would hold the product lock and the heap for a request that is refused anyway.
     *
     * <p>The test is exact in the direction that matters. The combinations still to create are at
     * least {@code total - existing} (the product has {@code existing} variants, at most that many of
     * them in the matrix), so a request over the cap by that measure is over it for certain; one that
     * passes here may still be refused by {@link #missing} once the real overlap is known. The product
     * saturates at {@link Long#MAX_VALUE} instead of overflowing.</p>
     */
    public static void requireWithinCap(List<Integer> axisSizes, int existing) {
        if (axisSizes.isEmpty()) {
            return;
        }
        long total = 1;
        for (int size : axisSizes) {
            if (size <= 0) {
                return;
            }
            total = total > Long.MAX_VALUE / size ? Long.MAX_VALUE : total * size;
        }
        long atLeastMissing = total - existing;
        if (atLeastMissing > MAX_PER_CALL) {
            throw new BusinessException(ErrorCode.VARIANT_MATRIX_TOO_LARGE,
                    "%s variants would be created; at most %d per call".formatted(
                            total == Long.MAX_VALUE ? "Far too many" : atLeastMissing + " or more", MAX_PER_CALL));
        }
    }

    public static List<Combination> of(List<List<AxisOption>> optionsPerAxisInOrder) {
        if (optionsPerAxisInOrder.isEmpty()) {
            return List.of();
        }
        List<List<AxisOption>> rows = new ArrayList<>();
        rows.add(List.of());
        for (List<AxisOption> axis : optionsPerAxisInOrder) {
            List<List<AxisOption>> next = new ArrayList<>(rows.size() * axis.size());
            for (List<AxisOption> row : rows) {
                for (AxisOption option : axis) {
                    List<AxisOption> extended = new ArrayList<>(row);
                    extended.add(option);
                    next.add(extended);
                }
            }
            rows = next;
        }
        return rows.stream().map(Combination::new).toList();
    }

    /**
     * The combinations whose key is not in {@code existing}. The limit counts what is left, so
     * running the same generation again after a partial one converges instead of failing.
     */
    public static <K> List<Combination> missing(List<Combination> all, Set<K> existing,
                                                Function<Combination, K> key) {
        List<Combination> missing = all.stream().filter(c -> !existing.contains(key.apply(c))).toList();
        if (missing.size() > MAX_PER_CALL) {
            throw new BusinessException(ErrorCode.VARIANT_MATRIX_TOO_LARGE,
                    "%d variants would be created; at most %d per call".formatted(missing.size(), MAX_PER_CALL));
        }
        return missing;
    }
}
