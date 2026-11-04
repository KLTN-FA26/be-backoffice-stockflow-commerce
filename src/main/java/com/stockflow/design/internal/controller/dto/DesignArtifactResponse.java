package com.stockflow.design.internal.controller.dto;

import java.time.Instant;
import java.util.UUID;

public record DesignArtifactResponse(UUID artifactId, UUID designId, com.stockflow.design.api.DesignArtifactRole role, String originalName,
                                     String contentType, long sizeBytes, Instant storedAt, String checksum) {
}
