package com.stockflow.product.internal.controller.dto;

import java.util.List;
import java.util.UUID;

/** The anonymous storefront view: published images of one variant and their CDN URLs only. */
public record PublicGalleryResponse(UUID productId, UUID variantId, String sku, List<Image> images) {

    public record Image(UUID imageId, String altText, boolean primary, String url, List<Rendition> renditions) {
    }

    public record Rendition(int edge, int width, int height, String contentType, String url) {
    }
}
