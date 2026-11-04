package com.stockflow.product.internal.controller.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The whole descriptive set of a product. Which of {@code valueIds}, {@code text}, {@code number} and
 * {@code bool} may be present depends on the attribute type, which only the service can see.
 */
public record ReplaceDescriptiveAttributesRequest(
        @NotNull(message = "attributes is required")
        List<@NotNull(message = "an entry must not be null") @Valid Entry> attributes
) {

    public record Entry(
            @NotNull(message = "attributeId is required")
            UUID attributeId,

            Integer sortOrder,

            List<@NotNull(message = "a value id must not be null") UUID> valueIds,

            @Size(max = 2000, message = "text must be at most 2000 characters")
            String text,

            @Digits(integer = 14, fraction = 4, message = "number allows 14 integer and 4 fraction digits")
            BigDecimal number,

            Boolean bool
    ) {
    }
}
