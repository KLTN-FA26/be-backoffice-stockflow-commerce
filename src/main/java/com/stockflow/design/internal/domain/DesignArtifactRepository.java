package com.stockflow.design.internal.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Resolve the owning draft before looking up its attachments. */
public interface DesignArtifactRepository {
    /** The design, locked when {@code forUpdate}; whoever asks, the caller decides who may see it. */
    Optional<DesignDraft> findDraft(UUID designId, boolean forUpdate);
    boolean hasSnapshot(UUID designId);
    /** Whether a quote request on the design is open: what lets the sales staff read it (plan D7). */
    boolean quoteRequestOpen(UUID designId);
    void attach(DesignArtifact artifact);
    List<DesignArtifact> findArtifacts(UUID designId);
}
