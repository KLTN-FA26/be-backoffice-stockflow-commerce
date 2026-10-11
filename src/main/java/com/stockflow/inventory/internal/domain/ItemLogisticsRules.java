package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.ItemLogistics;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * The rules of an inventory item's logistics, stated here so a bad value is a 400 with a reason; the
 * {@code ck_inventory_items_*} constraints state them again, so a manual UPDATE cannot bypass them.
 */
public final class ItemLogisticsRules {

    private static final Pattern UNIT = Pattern.compile("[A-Z0-9_]{1,16}");

    private ItemLogisticsRules() {
    }

    public static void validate(ItemLogistics l) {
        if (l.unitOfMeasure() == null || !UNIT.matcher(l.unitOfMeasure()).matches()) {
            throw invalid("unitOfMeasure must be 1-16 upper-case letters, digits or _");
        }
        if (l.barcode() != null && (l.barcode().isBlank() || l.barcode().length() > 64)) {
            throw invalid("barcode must be 1-64 characters, or absent");
        }
        positive(l.weightKg(), "weightKg");
        positive(l.lengthCm(), "lengthCm");
        positive(l.widthCm(), "widthCm");
        positive(l.heightCm(), "heightCm");
        positive(l.packageWeightKg(), "packageWeightKg");
        positive(l.packageLengthCm(), "packageLengthCm");
        positive(l.packageWidthCm(), "packageWidthCm");
        positive(l.packageHeightCm(), "packageHeightCm");
        if (l.packageCount() < 1 || l.packSize() < 1) {
            throw invalid("packageCount and packSize must be at least 1");
        }
        if (l.storageClass() == null) {
            throw invalid("storageClass is required");
        }
        if (l.shippingRestrictionNote() != null && l.shippingRestrictionNote().length() > 500) {
            throw invalid("shippingRestrictionNote must be at most 500 characters");
        }
    }

    private static void positive(BigDecimal value, String field) {
        if (value != null && value.signum() <= 0) {
            throw invalid(field + " must be positive");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
