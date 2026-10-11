package com.stockflow.product.api;

import java.util.UUID;

/** Public canonical media only; private storage keys never cross the module API. */
public record PublishedProductImage(
        UUID id,
        UUID variantId,
        String sku,
        String url,
        String altText,
        int sortOrder,
        boolean primary) {}
