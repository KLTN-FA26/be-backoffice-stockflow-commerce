package com.stockflow.fulfillment.api;

import java.time.Instant;
import java.util.UUID;

/** Short-lived private link returned only after task and order-line scope checks. */
public record FulfillmentArtifactAccess(
        UUID artifactId,
        String role,
        String originalName,
        String contentType,
        long sizeBytes,
        String checksum,
        String url,
        Instant expiresAt
) { }
