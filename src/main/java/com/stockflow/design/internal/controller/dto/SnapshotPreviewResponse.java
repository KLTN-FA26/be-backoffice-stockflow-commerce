package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/** The CUSTOMER_PREVIEW of a confirmed snapshot: {@code url} is private and stops working at {@code expiresAt}. */
public record SnapshotPreviewResponse(UUID snapshotId, UUID artifactId, String originalName, String contentType,
                                      long sizeBytes, String checksum, String url, Instant expiresAt) {
}
