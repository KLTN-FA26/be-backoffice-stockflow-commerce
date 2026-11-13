package com.stockflow.design.internal.repository;

import com.stockflow.design.internal.domain.DesignArtifact;
import com.stockflow.design.internal.domain.DesignArtifactRepository;
import com.stockflow.design.internal.domain.DesignDraft;
import com.stockflow.design.internal.entity.DesignArtifactJpaEntity;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class DesignArtifactRepositoryAdapter implements DesignArtifactRepository {
    private final DesignDraftJpaRepository drafts;
    private final DesignArtifactJpaRepository artifacts;
    private final DesignSnapshotJpaRepository snapshots;

    DesignArtifactRepositoryAdapter(DesignDraftJpaRepository drafts, DesignArtifactJpaRepository artifacts,
                                    DesignSnapshotJpaRepository snapshots) {
        this.drafts = drafts;
        this.artifacts = artifacts;
        this.snapshots = snapshots;
    }

    @Override
    public Optional<DesignDraft> findDraft(UUID designId, boolean forUpdate) {
        return (forUpdate ? drafts.lockById(designId) : drafts.findById(designId))
                .map(row -> new DesignDraft(row.getId(), row.getOwnerUserId(), row.getStatus(),
                        row.getCurrentArtifactId(), row.getTemplateId()));
    }

    @Override
    public boolean hasSnapshot(UUID designId) { return snapshots.existsByDraftId(designId); }
    @Override
    public boolean quoteRequestOpen(UUID designId) { return drafts.hasOpenQuoteRequest(designId); }


    @Override
    public void attach(DesignArtifact artifact) {
        var row = new DesignArtifactJpaEntity(artifact.id(), artifact.designId(), artifact.role(), artifact.file(), artifact.checksum());
        row.setUploadKey(artifact.uploadKey());
        row.renderedFrom(artifact.documentChecksum(), artifact.templateVersion());
        artifacts.save(row);
        // Already checked and locked by the service in this transaction.
        drafts.findById(artifact.designId()).orElseThrow().attachArtifact(artifact.role(), artifact.id());
    }

    @Override
    public List<DesignArtifact> findArtifacts(UUID designId) {
        return artifacts.findByDraftIdOrderByStoredAtDescIdDesc(designId).stream()
                .map(row -> new DesignArtifact(row.getId(), row.getDraftId(), row.getRole(), row.storedFile(), row.getChecksum(),
                        row.getUploadKey(), row.getDocumentChecksum(), row.getTemplateVersion()))
                .toList();
    }
}
