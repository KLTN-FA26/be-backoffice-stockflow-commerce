package com.stockflow.inventory.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.internal.domain.LocationId;

import java.util.List;
import java.util.Locale;

/**
 * What a counter or planner typed or scanned, made into values. A malformed SKU or code is the caller's mistake and
 * is answered as {@code VALIDATION_FAILED} naming the field; left to the value object's
 * {@code IllegalArgumentException} it would reach the handler as a generic 400 logged as a server fault.
 */
final class CycleCountInput {

    private CycleCountInput() {
    }

    static Sku sku(String field, String value) {
        try {
            return new Sku(value);
        } catch (IllegalArgumentException malformed) {
            throw invalid(field, "%s is not a valid SKU: %s".formatted(field, value));
        }
    }

    static LocationId location(String field, String value) {
        String code = upper(value);
        if (code == null || !code.matches("[A-Z0-9]+(-[A-Z0-9]+)+")) {
            throw invalid(field, "%s is not a location code: %s".formatted(field, value));
        }
        return new LocationId(code);
    }

    /** Scanners and people disagree on case; every code and LPN is stored upper-cased. Blank is absent. */
    static String upper(String value) {
        return value == null || value.isBlank() ? null : value.strip().toUpperCase(Locale.ROOT);
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message,
                List.of(new FieldError(field, message, "Malformed")));
    }
}
