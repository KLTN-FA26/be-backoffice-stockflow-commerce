package com.stockflow.design.internal.controller.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.stockflow.design.api.DesignArtifactRole;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An approved design's snapshot (kltn-docs 12 §5.2): the files, and for a customer's design the layout as approved
 * ({@code document}, written as stored, and its checksum), the images it places and the customer's SKU issued for it.
 */
public record DesignSnapshotResponse(UUID id, UUID draftId, UUID artifactId, String checksum, String spec,
                                     UUID confirmedBy, Instant confirmedAt, List<Artifact> artifacts,
                                     @JsonRawValue String document, String documentChecksum, List<Asset> assets,
                                     String customerSku) {
    public record Artifact(UUID artifactId, DesignArtifactRole role, String originalName,
                           String contentType, long sizeBytes, String checksum) { }

    public record Asset(UUID assetId, String originalName, String contentType, long sizeBytes, String checksum,
                        int widthPx, int heightPx) { }
}
