package com.stockflow.design.api;

import java.time.Instant;
import java.util.UUID;

/** Short-lived private delivery capability for an already-authorized fulfillment task. */
public record FulfillmentArtifact(UUID artifactId, DesignArtifactRole role, String originalName,
                                  String contentType, long sizeBytes, String checksum,
                                  String url, Instant expiresAt) { }
