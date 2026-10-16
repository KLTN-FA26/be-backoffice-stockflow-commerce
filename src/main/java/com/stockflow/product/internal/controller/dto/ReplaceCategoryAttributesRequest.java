package com.stockflow.product.internal.controller.dto;

import com.stockflow.product.internal.domain.AttributeRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record ReplaceCategoryAttributesRequest(
        @NotNull(message = "attributes is required")
        List<@NotNull(message = "an entry must not be null") @Valid Entry> attributes
) {

    public record Entry(
            @NotNull(message = "attributeId is required")
            UUID attributeId,

            @NotNull(message = "defaultRole is required")
            AttributeRole defaultRole,

            boolean required,

            Integer sortOrder
    ) {
    }
}
