package com.stockflow.product.internal.controller.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Images of one variant to put on the storefront, by someone other than who uploaded them. */
public record PublishMediaRequest(
        @NotEmpty(message = "mediaIds is required")
        @Size(max = 20, message = "at most 20 images at once")
        List<@NotNull UUID> mediaIds
) {
}
