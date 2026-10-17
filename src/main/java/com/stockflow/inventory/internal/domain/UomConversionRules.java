package com.stockflow.inventory.internal.domain;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionDraft;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The rules of a unit of measure (docs 01 §3, BR-09, BR-10), stated so a bad value is a 400 naming its
 * field; {@code ck_uom_conversions_*} state them again.
 */
public final class UomConversionRules {

    private static final Pattern UNIT = Pattern.compile("[A-Z0-9_]{1,16}");

    private UomConversionRules() {
    }

    /** A packing unit as declared or replaced. */
    public static void validatePacking(UomConversionDraft d) {
        if (d.unitOfMeasure() == null || !UNIT.matcher(d.unitOfMeasure()).matches()) {
            throw invalid("unitOfMeasure", "unitOfMeasure must be 1-16 upper-case letters, digits or _");
        }
        // BR-09: "hệ số phải là số nguyên dương".
        if (d.factor() < 1) {
            throw invalid("factor", "factor must be a positive whole number of base units");
        }
        positive(d.weightKg(), "weightKg");
        positive(d.lengthCm(), "lengthCm");
        positive(d.widthCm(), "widthCm");
        positive(d.heightCm(), "heightCm");
        retiredIsNotDefault(d);
    }

    /**
     * The base unit: only its default purchase mark is edited here, and it can never be retired. Its code,
     * factor, size and weight in the draft are ignored: they are the item's logistics, edited there.
     */
    public static void validateBase(UomConversion current, UomConversionDraft d) {
        if (!d.active()) {
            throw new BusinessException(ErrorCode.UOM_CONVERSION_IN_USE, "The base unit cannot be retired");
        }
        if (!d.defaultPurchase() && current.defaultPurchase()) {
            throw invalid("defaultPurchase",
                    "The base unit stays the default purchase unit until another unit is marked");
        }
    }

    private static void retiredIsNotDefault(UomConversionDraft d) {
        if (!d.active() && d.defaultPurchase()) {
            throw invalid("defaultPurchase", "A retired unit cannot be the default purchase unit");
        }
    }

    private static void positive(BigDecimal value, String field) {
        if (value != null && value.signum() <= 0) {
            throw invalid(field, field + " must be positive");
        }
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message,
                List.of(new FieldError(field, message, ErrorCode.VALIDATION_FAILED.name())));
    }
}
