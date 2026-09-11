package com.stockflow.design.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of an immutable confirmed design snapshot (table {@code design.design_snapshot}).
 *
 * <p>STARTER ENTITY. Once confirmed it is never edited — a change produces a new snapshot. Order
 * stores only this id + checksum, never a copy of the artifact.</p>
 */
@Entity
@Table(name = "design_snapshot", schema = "design",
        uniqueConstraints = @UniqueConstraint(name = "uk_design_snapshot_checksum", columnNames = "checksum"))
public class DesignSnapshotJpaEntity extends BaseEntity {

    @Column(name = "draft_id", nullable = false)
    private UUID draftId;

    @Column(name = "checksum", nullable = false, length = 128)
    private String checksum;

    @Column(name = "artifact_url", nullable = false, length = 500)
    private String artifactUrl;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    protected DesignSnapshotJpaEntity() {
    }

    public DesignSnapshotJpaEntity(UUID id, UUID draftId, String checksum, String artifactUrl,
                                   Instant confirmedAt) {
        super(id);
        this.draftId = draftId;
        this.checksum = checksum;
        this.artifactUrl = artifactUrl;
        this.confirmedAt = confirmedAt;
    }

    public UUID getDraftId() { return draftId; }
    public String getChecksum() { return checksum; }
    public String getArtifactUrl() { return artifactUrl; }
    public Instant getConfirmedAt() { return confirmedAt; }
}
