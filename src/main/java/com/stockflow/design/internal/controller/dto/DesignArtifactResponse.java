package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

public record DesignArtifactResponse(UUID artifactId, UUID designId, String originalName,
                                     String contentType, long sizeBytes, Instant storedAt, String checksum) {
}
