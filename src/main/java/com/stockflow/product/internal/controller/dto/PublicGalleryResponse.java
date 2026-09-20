package com.stockflow.product.internal.controller.dto;

import java.util.List;
import java.util.UUID;

/** The anonymous storefront view: approved images and their CDN URLs only. */
public record PublicGalleryResponse(UUID productId, UUID variantId, List<Image> images) {
    public record Image(UUID imageId, String caption, List<Rendition> renditions) { }
    public record Rendition(int edge, int width, int height, String contentType, String url) { }
}
