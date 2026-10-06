package com.stockflow.design.internal.domain;

import com.stockflow.design.api.DesignArtifactRole;

import java.util.UUID;

/** Immutable snapshot manifest entry. Storage keys stay inside the design module. */
public record DesignSnapshotArtifact(UUID artifactId, DesignArtifactRole role, String storageKey,
                                     String originalName, String contentType, long sizeBytes,
                                     String checksum) { }
