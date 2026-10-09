package com.stockflow.catalog.internal.controller.dto;

import java.util.List;
import java.util.UUID;

public record CatalogGalleryResponse(List<Image> images) {
    public record Image(
            UUID id,
            UUID variantId,
            String sku,
            String url,
            String altText,
            int sortOrder,
            boolean primary) {}
}
