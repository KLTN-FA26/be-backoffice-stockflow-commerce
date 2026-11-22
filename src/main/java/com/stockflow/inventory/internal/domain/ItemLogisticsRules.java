package com.stockflow.inventory.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.inventory.api.ItemLogistics;

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
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
