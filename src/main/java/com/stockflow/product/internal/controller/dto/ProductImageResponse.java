package com.stockflow.product.internal.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The product's own image (one per product, ADR-0012): what the storefront's lists and category pages show for the
 * product. The original is private and its key is never sent; each rendition carries a short-lived link showing it on
 * a read ({@code url} is null on the answer to a write).
 */
public record ProductImageResponse(UUID productId, String originalName, String contentType, long sizeBytes,
                                   Instant storedAt, String altText, List<Rendition> renditions) {

    public record Rendition(int edge, int width, int height, String contentType, long sizeBytes, String url) {
    }
}
