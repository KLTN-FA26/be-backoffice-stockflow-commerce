package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of {@code POST} and {@code PUT /api/v1/products/{productId}/variants/{variantId}/customization-templates}
 * (kltn-docs 01 §4.10). The print areas' position and shape are computed from {@code model}; each area entry only names
 * the area and sets its margins.
 */
@Schema(description = "A customization template: the product model, the upload limits, and each print area's margins")
public record CustomizationTemplateRequest(

        @Schema(example = "CUP12-BODY", description = "Create only: 1-50 capital letters, digits or hyphens, unique per "
                + "variant; ignored on update (a template keeps its code)")
        @Size(max = 50)
        String code,

        @Schema(example = "Ly giấy 12oz - thân ly")
        @NotBlank(message = "name is required")
        @Size(max = 255)
        String name,

        @Schema(example = "300", description = "Lowest effective resolution of a placed image, 72-2400; 300 when "
                + "omitted (B34)")
        Integer minDpi,

        @Schema(example = "CMYK", description = "CMYK or RGB; CMYK when omitted")
        String colorMode,

        @Schema(description = "Types a customer may upload into a design: image/png, image/jpeg, image/webp")
        @NotEmpty(message = "allowedFileFormats is required")
        List<String> allowedFileFormats,

        @Schema(example = "20", description = "Largest upload in MB, 1-50")
        @NotNull(message = "maxFileSizeMb is required")
        Integer maxFileSizeMb,

        @Schema(example = "4", description = "Most ink colours the producer prints; omit for no limit")
        Integer maxColors,

        @Schema(description = "Fonts a design may use; omit to allow every font the studio offers")
        List<String> allowedFontCodes,

        @Schema(description = "The parametric product the print areas are computed from")
        @NotNull(message = "model is required")
        @Valid
        ProductModelRequest model,

        @Schema(description = "One entry per print area the model has: BODY for a cup; FRONT, BACK, LEFT, RIGHT for a bag")
        @NotEmpty(message = "printAreas is required")
        @Valid
        List<PrintAreaRequest> printAreas,

        @Schema(description = "Update only: the template's version as last read; a changed template answers 409 "
                + "OPTIMISTIC_LOCK")
        Long version,

        @Schema(description = "Optional: which mesh of the template's .glb each print area is drawn on. Omitted keeps "
                + "the template's map (empty on create); an empty surfaces list clears it. A print area the model does "
                + "not have, one mapped twice, or a meshName outside 1-100 characters is 422 "
                + "PRINT_CONFIG_SURFACES_INVALID with fieldErrors model3d.surfaces[i].printAreaCode / .meshName")
        Model3d model3d
) {

    /** The template's 3D surfaces; the file itself is uploaded on its own ({@code PUT .../model-file}). */
    public record Model3d(List<Surface> surfaces) {
    }

    /** The mesh a print area is drawn on. */
    public record Surface(
            @Schema(example = "BODY", description = "A print area of the template's model: BODY; or FRONT, BACK, "
                    + "LEFT, RIGHT")
            String printAreaCode,
            @Schema(example = "print:BODY", description = "The mesh's name in the .glb, 1-100 characters")
            String meshName) {
    }
}
