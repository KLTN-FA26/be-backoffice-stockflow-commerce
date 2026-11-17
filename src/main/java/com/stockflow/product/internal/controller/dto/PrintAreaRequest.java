package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One print area of a template: which, its name, and its margins. Where it lies follows from the model. */
@Schema(description = "A print area's margins; position and shape are computed from the model")
public record PrintAreaRequest(
        @Schema(example = "BODY", description = "BODY (cup) or FRONT, BACK, LEFT, RIGHT (bag)")
        @NotBlank(message = "printAreas[].code is required")
        String code,

        @Schema(example = "Thân ly")
        @NotBlank(message = "printAreas[].name is required")
        @Size(max = 255)
        String name,

        @Schema(example = "3", description = "Inset from the trim nothing important may cross, mm")
        @NotNull(message = "printAreas[].safeMarginMm is required")
        Double safeMarginMm,

        @Schema(example = "2", description = "Outset past the trim artwork must reach, mm (0-20)")
        @NotNull(message = "printAreas[].bleedMm is required")
        Double bleedMm
) {
}
