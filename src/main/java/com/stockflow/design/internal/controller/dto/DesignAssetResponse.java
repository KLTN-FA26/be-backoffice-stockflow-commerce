package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * An image of a design (the storefront's P4). {@code widthPx} and {@code heightPx} are the server's measurement of the
 * image upright, after its EXIF orientation: what the studio's resolution check and the server's preflight both use.
 */
public record DesignAssetResponse(UUID assetId, UUID designId, String originalName, String contentType,
                                  long sizeBytes, String checksum, int widthPx, int heightPx, Instant storedAt) {
}
