package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Body of a customization template's activation and deactivation: the version the screen read. */
@Schema(description = "The template's version as last read")
public record TemplateVersionRequest(

        @Schema(description = "A changed template answers 409 OPTIMISTIC_LOCK")
        @NotNull(message = "version is required")
        Long version
) {
}
