package com.stockflow.fulfillment.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

/** A short-lived private download link; never persist or log the URL. */
public record FulfillmentArtifactAccessResponse(UUID artifactId, String role, String originalName,
                                                String contentType, long sizeBytes, String checksum,
                                                String url, Instant expiresAt) {
}
