package com.stockflow.product.internal.controller.dto;

import java.util.List;

/**
 * An image uploaded for a description (ADR-0013). {@code url} is public and lasting: put it in the editor's image
 * ({@code setImage({ src: url })}). {@code renditions} are the other display sizes, for a {@code srcset}.
 */
public record DescriptionImageResponse(String url, int width, int height, List<Rendition> renditions) {

    public record Rendition(int edge, int width, int height, String url) {
    }
}
