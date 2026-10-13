package com.stockflow.product.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Every combination of one option per axis. Enumerated with the first axis varying slowest, so the
 * first combination is the first option of every axis, which is the one the default variant becomes.
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
     * The combinations whose signature is not in {@code existing}. The limit counts what is left, so
     * running the same generation again after a partial one converges instead of failing.
     */
    public static List<Combination> missing(List<Combination> all, Set<VariantSignature> existing,
                                            Function<Combination, VariantSignature> sign) {
        List<Combination> missing = all.stream().filter(c -> !existing.contains(sign.apply(c))).toList();
        if (missing.size() > MAX_PER_CALL) {
            throw new BusinessException(ErrorCode.VARIANT_MATRIX_TOO_LARGE,
                    "%d variants would be created; at most %d per call".formatted(missing.size(), MAX_PER_CALL));
        }
        return missing;
    }
}
