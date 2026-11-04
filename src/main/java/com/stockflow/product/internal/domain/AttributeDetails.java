package com.stockflow.product.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

/**
 * The editable description of an attribute. Lengths mirror the column sizes so a bad value is
 * refused with a reason rather than a constraint violation.
 */
public record AttributeDetails(String name, String unit, boolean filterable, boolean searchable, int sortOrder) {

    static final int NAME_MAX_LENGTH = 255;
    static final int UNIT_MAX_LENGTH = 20;

    public AttributeDetails {
        name = name == null ? "" : name.strip();
        if (name.isEmpty() || name.length() > NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Attribute name must be 1-%d characters".formatted(NAME_MAX_LENGTH));
        }
        unit = unit == null || unit.isBlank() ? null : unit.strip();
        if (unit != null && unit.length() > UNIT_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Attribute unit must be at most %d characters".formatted(UNIT_MAX_LENGTH));
        }
    }
}
