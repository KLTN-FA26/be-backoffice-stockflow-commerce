package com.stockflow.warehouse.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The input checks every map aggregate repeats: a measure that fits {@code NUMERIC(10,3)}, a
 * required or optional text that fits its column. Each failure is a {@code 400 VALIDATION_FAILED}
 * naming the field - never an {@code IllegalArgumentException}, which the exception handler logs as
 * a bug.
 */
final class DomainChecks {

    private static final int SCALE = 3;
    /** {@code NUMERIC(10,3)}: seven digits before the point. */
    private static final BigDecimal COLUMN_LIMIT = new BigDecimal("10000000");

    private DomainChecks() {
    }

    /**
     * A measure in the map unit at scale 3. A finer value is refused, not rounded: silently moving
     * something by a fraction of a millimetre can turn a touching pair into an overlapping one.
     */
    static BigDecimal measure(String name, BigDecimal value) {
        if (value == null) {
            throw invalid(name + " is required");
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException finerThanAMillimetre) {
            throw invalid("%s has more than %d decimals: %s".formatted(name, SCALE, value.toPlainString()));
        }
        if (scaled.abs().compareTo(COLUMN_LIMIT) >= 0) {
            throw invalid("%s is too large: %s".formatted(name, value.toPlainString()));
        }
        return scaled;
    }

    static BigDecimal positiveMeasure(String name, BigDecimal value) {
        BigDecimal scaled = measure(name, value);
        if (scaled.signum() <= 0) {
            throw invalid(name + " must be greater than zero");
        }
        return scaled;
    }

    /** Trimmed, not blank, at most {@code maxLength} characters. */
    static String requiredText(String name, String value, int maxLength) {
        String trimmed = optionalText(name, value, maxLength);
        if (trimmed == null) {
            throw invalid(name + " is required");
        }
        return trimmed;
    }

    /** Trimmed; blank becomes {@code null}. */
    static String optionalText(String name, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > maxLength) {
            throw invalid("%s may be at most %d characters".formatted(name, maxLength));
        }
        return trimmed;
    }

    static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
