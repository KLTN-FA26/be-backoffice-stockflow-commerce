package com.stockflow.design.internal.controller.dto;

import java.util.List;
import java.util.UUID;

/**
 * The template a design is drawn on, in the shape of one template of the storefront's P2
 * ({@code GET /catalog/products/{slug}/customization}), so the studio parses both alike; {@code active} says whether
 * customers may still start designs on it - a draft keeps its template either way (kltn-docs 12 B39).
 */
public record DesignTemplateResponse(UUID templateId, String code, String name, String sku, int minDpi,
                                     String colorMode, List<String> allowedFileFormats, int maxFileSizeMb,
                                     Integer maxColors, List<String> allowedFontCodes, boolean active, Model model,
                                     List<Area> printAreas) {

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
