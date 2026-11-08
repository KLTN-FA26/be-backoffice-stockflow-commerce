package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A file in a design project's library (kltn-docs 12 §4.9 step 29), measured upright as a design's images are; placed
 * on a design, it is copied into it.
 */
public record DesignProjectAssetResponse(UUID assetId, UUID projectId, String originalName, String contentType,
                                         long sizeBytes, String checksum, int widthPx, int heightPx,
                                         Instant storedAt) {
}
