package com.stockflow.design.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Resolve the owning draft before looking up its attachments. */
public interface DesignArtifactRepository {
    Optional<DesignDraft> findDraft(UUID designId, UUID userId, boolean forUpdate);
    boolean hasSnapshot(UUID designId);
    void attach(DesignArtifact artifact);
    void recordEditor(UUID designId, UUID userId);
    List<DesignArtifact> findArtifacts(UUID designId);
}
