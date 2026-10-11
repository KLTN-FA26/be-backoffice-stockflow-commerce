package com.stockflow.product.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A new brand. {@code code} is upper-cased by the server and fixed; the slug is derived from it. */
public record CreateBrandRequest(
        @NotBlank(message = "code is required")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,49}",
                message = "code is 1-50 letters, digits, '_' or '-', starting with a letter or digit")
        String code,

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        String logoUrl
) {
}
