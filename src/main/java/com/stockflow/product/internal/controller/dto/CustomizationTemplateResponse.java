package com.stockflow.product.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * A customization template as the back office reads it: the storefront's shape (kltn-docs 12; the studio's
 * {@code customizationTemplateSchema}) plus what only staff see - whether it is active, its reference file, its
 * version.
 */
@Schema(description = "A customization template with its computed print areas")
public record CustomizationTemplateResponse(
        UUID templateId,
        UUID productId,
        UUID variantId,
        String sku,
        String code,
        String name,
        int minDpi,
        String colorMode,
        List<String> allowedFileFormats,
        int maxFileSizeMb,
        Integer maxColors,
        List<String> allowedFontCodes,
        boolean active,
        @Schema(description = "Absent for a template written before templates had a model: enter one to activate it")
        Model model,
        List<Area> printAreas,
        @Schema(description = "File name of the attached reference die-line, if any")
        String referenceFileName,
        long version,
        @Schema(description = "The template's 3D model: whether a .glb is attached, its name and SHA-256, and which "
                + "mesh each print area is drawn on. Always present")
        Model3d model3d
) {

    /**
     * The template's {@code .glb} and its surfaces. {@code fileChecksum} is the SHA-256 (hex) of the file the model's
     * measurements were taken from; null with no file.
     */
    public record Model3d(boolean hasFile, String fileName, String fileChecksum, List<Surface> surfaces) {
    }

    /** The mesh a print area is drawn on. */
    public record Surface(String printAreaCode, String meshName) {
    }

    /** The parametric model; only the fields of its {@code kind} are present. */
    public record Model(String kind, Double topDiameterMm, Double bottomDiameterMm, Double heightMm, Double rimMm,
                        Double seamMm, Double bandTopOffsetMm, Double bandBottomOffsetMm, Double widthMm,
                        Double gussetMm, Double topFoldMm, String handle, String material) {
    }

    /** A computed print area; its size is the trimmed area's flat bounding box (the bleed lies outside it). */
    public record Area(String code, String name, double xMm, double yMm, double widthMm, double heightMm,
                       double safeMarginMm, double bleedMm, Shape shape, UvMapping uvMapping) {
    }

    /** {@code rect}, or {@code annular-sector} with its radii, sweep and glue flap. */
    public record Shape(String type, Double innerRadiusMm, Double outerRadiusMm, Double sweepRad, Double seamMm) {
    }

    /** The 3D model part the area is wrapped on. */
    public record UvMapping(String meshPart) {
    }
}
