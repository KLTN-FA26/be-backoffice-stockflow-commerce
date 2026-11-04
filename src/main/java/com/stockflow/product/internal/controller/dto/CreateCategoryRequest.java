package com.stockflow.product.internal.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** A new node of the category tree. Its code and its parent are fixed once created. */
public record CreateCategoryRequest(
        @NotBlank(message = "code is required")
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_-]{0,49}",
                message = "code is 1-50 letters, digits, '_' or '-', starting with a letter or digit")
        String code,

        UUID parentId,

        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        Integer sortOrder,

        String imageUrl,

        @Size(max = 255, message = "seoTitle must be at most 255 characters")
        String seoTitle,

        String seoDescription
) {
}
