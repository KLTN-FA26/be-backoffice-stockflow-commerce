package com.stockflow.product.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/** The whole axis list of a product; a null {@code sortOrder} means the entry's position in the list. */
public record ReplaceAxesRequest(
        @NotNull(message = "axes is required")
        List<@NotNull(message = "an axis must not be null") @Valid Axis> axes
) {

    public record Axis(
            @NotNull(message = "attributeId is required")
            UUID attributeId,

            Integer sortOrder,

            @NotEmpty(message = "an axis needs at least one option")
            List<@NotNull(message = "an option value id must not be null") UUID> optionValueIds
    ) {
    }
}
