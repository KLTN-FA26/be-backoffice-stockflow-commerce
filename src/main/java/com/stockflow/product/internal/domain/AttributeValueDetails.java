package com.stockflow.product.internal.domain;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * The editable description of one allowed value. Whether a colour is required depends on the
 * owning attribute's type, so that is checked by {@link Attribute}; only the shape is checked here.
 */
public record AttributeValueDetails(String label, BigDecimal valueNumber, String hexColor, int sortOrder) {

    static final int LABEL_MAX_LENGTH = 255;

    public AttributeValueDetails {
        label = label == null ? "" : label.strip();
        if (label.isEmpty() || label.length() > LABEL_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Value label must be 1-%d characters".formatted(LABEL_MAX_LENGTH));
        }
        hexColor = hexColor == null || hexColor.isBlank() ? null : hexColor.strip().toUpperCase(Locale.ROOT);
    }
}
