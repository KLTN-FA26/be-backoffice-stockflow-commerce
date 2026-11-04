package com.stockflow.product.internal.controller.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One image of a variant. The original is private: {@code download-url} gives a short-lived link.
 * {@code url} is set only for an image carried over as an external URL.
 */
public record MediaResponse(UUID mediaId, UUID variantId, String kind, String url, String originalName,
                            String contentType, Long sizeBytes, Instant storedAt, List<Rendition> renditions,
                            String altText, int sortOrder, boolean primary, boolean published, Instant publishedAt,
                            UUID publishedBy, String uploadedBy, Instant uploadedAt, long version) {

    public record Rendition(int edge, int width, int height, String contentType, long sizeBytes) {
    }
}
