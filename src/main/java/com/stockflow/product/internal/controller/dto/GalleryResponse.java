package com.stockflow.product.internal.controller.dto;

import java.util.List;
import java.util.UUID;

/** {@code working} is the draft being edited; {@code published} is what the storefront serves. */
public record GalleryResponse(long revision, List<GalleryItemResponse> working,
                              List<GalleryItemResponse> published, UUID editedBy, UUID approvedBy) {
}
