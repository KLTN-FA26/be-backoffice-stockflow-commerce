package com.stockflow.product.internal.controller.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** A new allowed value of an attribute. Its code is fixed once created. */
public record CreateAttributeValueRequest(
        @NotBlank(message = "code is required")
        String code,

        @NotBlank(message = "label is required")
        @Size(max = 255, message = "label must be at most 255 characters")
        String label,

        @Digits(integer = 14, fraction = 4, message = "valueNumber must have at most 14 integer and 4 fraction digits")
        BigDecimal valueNumber,

        String hexColor,

        Integer sortOrder
) {
}
