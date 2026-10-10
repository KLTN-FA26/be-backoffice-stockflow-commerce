package com.stockflow.product.internal.controller.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Alt text, position and cover of one image. {@code primary=true} moves the cover to it. */
public record DescribeMediaRequest(
        @Size(max = 255, message = "altText must be at most 255 characters")
        String altText,

        @PositiveOrZero(message = "sortOrder must not be negative")
        Integer sortOrder,

        boolean primary
) {
}
