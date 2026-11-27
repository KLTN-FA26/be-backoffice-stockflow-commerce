package com.stockflow.product.internal.controller.dto;

import jakarta.validation.constraints.Size;

/** The alt text of the product's image; blank or null removes it. */
public record DescribeProductImageRequest(
        @Size(max = 255, message = "altText must be at most 255 characters")
        String altText
) {
}
