package com.stockflow.catalog.internal.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/catalog/products/{slug}/customization} (the storefront's P2): the shape the design studio parses
 * with its {@code customizationDtoSchema} - every length in millimetres, the model and shapes discriminated by
 * {@code kind} and {@code type}.
 */
@Schema(description = "The templates a customer may design on, one per ACTIVE variant that has an active template")
public record CatalogCustomizationResponse(UUID productId, String kind, List<Template> templates) {

    /** One variant's active template. */
    public record Template(UUID templateId, String code, String name, String sku, int minDpi, String colorMode,
                           List<String> allowedFileFormats, int maxFileSizeMb, Integer maxColors,
                           List<String> allowedFontCodes, Model model, List<Area> printAreas) {
    }

    /** The parametric model; only the fields of its {@code kind} are present. */
    public record Model(String kind, Double topDiameterMm, Double bottomDiameterMm, Double heightMm, Double rimMm,
                        Double seamMm, Double bandTopOffsetMm, Double bandBottomOffsetMm, Double widthMm,
                        Double gussetMm, Double topFoldMm, String handle, String material) {
    }

    /** A print area; its size is the trimmed area's flat bounding box (the bleed lies outside it). */
    public record Area(String code, String name, double xMm, double yMm, double widthMm, double heightMm,
                       double safeMarginMm, double bleedMm, Shape shape, UvMapping uvMapping) {
    }

    /** {@code rect}, or {@code annular-sector} with its trim radii, sweep and glue flap. */
    public record Shape(String type, Double innerRadiusMm, Double outerRadiusMm, Double sweepRad, Double seamMm) {
    }

    /** The 3D model part the area is wrapped on. */
    public record UvMapping(String meshPart) {
    }
}
