package com.stockflow.product.internal.controller.dto;

import com.stockflow.product.internal.domain.AttributeDataType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A new dictionary attribute. Its code and data type are fixed once created. */
public record CreateAttributeRequest(
        @NotBlank(message = "code is required")
        String code,

        @NotNull(message = "dataType is required")
        AttributeDataType dataType,

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Size(max = 20, message = "unit must be at most 20 characters")
        String unit,

        boolean filterable,

        boolean searchable,

        Integer sortOrder
) {
}
