package com.stockflow.design.internal.controller.dto;

import com.stockflow.design.api.DesignArtifactRole;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DesignSnapshotResponse(UUID id, UUID draftId, UUID artifactId, String checksum, String spec,
                                     UUID confirmedBy, Instant confirmedAt, List<Artifact> artifacts) {
    public record Artifact(UUID artifactId, DesignArtifactRole role, String originalName,
                           String contentType, long sizeBytes, String checksum) { }
}
