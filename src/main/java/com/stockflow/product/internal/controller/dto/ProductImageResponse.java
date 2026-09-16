package com.stockflow.product.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/** Legacy URLs remain readable; new uploads use the guarded download-url endpoint. */
public record ProductImageResponse(UUID imageId, int sortOrder, String legacyUrl,
                                   String originalName, String contentType, Long sizeBytes,
                                   Instant storedAt) {
}
