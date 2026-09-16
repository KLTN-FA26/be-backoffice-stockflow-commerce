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
    public Optional<DesignDraft> findDraft(UUID designId, UUID userId, boolean forUpdate) {
        if (userId == null) { return Optional.empty(); }
        return (forUpdate ? drafts.lockAccessible(designId, userId) : drafts.findAccessible(designId, userId))
                .map(row -> new DesignDraft(row.getId(), row.getOwnerUserId(), row.getAssignedUserId(),
                        row.getStatus(), row.getCurrentArtifactId()));
    }

    @Override
    public boolean hasSnapshot(UUID designId) { return snapshots.existsByDraftId(designId); }

    @Override
    public void attach(DesignArtifact artifact) {
        artifacts.save(new DesignArtifactJpaEntity(artifact.id(), artifact.designId(),
                artifact.file(), artifact.checksum()));
        // Already checked and locked by the service in this transaction.
        drafts.findById(artifact.designId()).orElseThrow().attachArtifact(artifact.id());
    }

    @Override
    public List<DesignArtifact> findArtifacts(UUID designId) {
        return artifacts.findByDraftIdOrderByStoredAtDescIdDesc(designId).stream()
                .map(row -> new DesignArtifact(row.getId(), row.getDraftId(), row.storedFile(), row.getChecksum()))
                .toList();
    }
}
