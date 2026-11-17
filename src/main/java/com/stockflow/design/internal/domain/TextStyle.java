package com.stockflow.design.internal.domain;

/**
 * The type style of a text in a design project's library (FE request 2026-11-13 E-1): the type fields of a layout's
 * text element, so the studio places the text exactly as it was saved. Stored as one JSON object
 * ({@code design_project_text.style}), whose CHECK states the same shape as the request's validation.
 *
 * @param align   {@code left}, {@code center} or {@code right}
 * @param outline the stroke around the glyphs, or {@code null} for none
 */
public record TextStyle(String fontCode, double sizePt, String color, boolean bold, boolean italic, String align,
                        double letterSpacingEm, double lineHeight, Outline outline) {

    /** A stroke around the glyphs: its colour and its width on the printed surface. */
    public record Outline(String color, double widthMm) {
    }
}
