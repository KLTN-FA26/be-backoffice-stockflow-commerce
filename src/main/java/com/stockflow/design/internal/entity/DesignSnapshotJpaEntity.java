package com.stockflow.design.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import com.stockflow.design.internal.domain.DesignSnapshotArtifact;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA mapping of an immutable confirmed design snapshot (table {@code design.design_snapshot}).
 *
 * <p>Once confirmed it is never edited — a change produces a new snapshot. Order
 * stores only this id + checksum, never a copy of the artifact.</p>
 */
@Entity
@Table(name = "design_snapshot", schema = "design")
public class DesignSnapshotJpaEntity extends BaseEntity {

    @Column(name = "draft_id", nullable = false)
    private UUID draftId;

    @Column(name = "checksum", nullable = false, length = 128)
    private String checksum;

    @Column(name = "artifact_url", nullable = false, length = 500)
    private String artifactUrl;

    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    @Column(name = "artifact_id", updatable = false)
    private UUID artifactId;
    @Column(name = "confirmed_by", updatable = false)
    private UUID confirmedBy;
    @Column(name = "spec", length = 4000, updatable = false)
    private String spec;
    @Column(name = "reviewed_by", updatable = false)
    private UUID reviewedBy;
    @Column(name = "review_notes", length = 2000, updatable = false)
    private String reviewNotes;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "artifact_manifest", nullable = false, updatable = false, columnDefinition = "jsonb")
    private List<DesignSnapshotArtifact> artifactManifest = List.of();

    public DesignSnapshotJpaEntity(UUID id, DesignDraftJpaEntity draft, DesignArtifactJpaEntity artifact,
                                   UUID actor, Instant now) {
        this(id, draft, List.of(artifact), actor, now);
    }

    public DesignSnapshotJpaEntity(UUID id, DesignDraftJpaEntity draft, List<DesignArtifactJpaEntity> artifacts,
                                   UUID actor, Instant now) {
        this(id, draft.getId(), manifestChecksum(toManifest(artifacts)), primary(artifacts).storedFile().key(), now);
        var primary = primary(artifacts);
        this.artifactId = primary.getId();
        this.confirmedBy = actor;
        this.spec = draft.getSpec();
        this.reviewedBy = draft.getReviewedBy();
        this.reviewNotes = draft.getReviewNotes();
        this.artifactManifest = toManifest(artifacts);
    }
    public UUID getArtifactId() { return artifactId; }
    public UUID getConfirmedBy() { return confirmedBy; }
    public String getSpec() { return spec; }
    public List<DesignSnapshotArtifact> getArtifactManifest() { return List.copyOf(artifactManifest); }

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

    public static String manifestChecksum(List<DesignSnapshotArtifact> manifest) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            manifest.stream().sorted(Comparator.comparing(value -> value.role().name())).forEach(value -> {
                digest.update(value.role().name().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(value.artifactId().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) ':');
                digest.update(value.checksum().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                digest.update((byte) '\n');
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static List<DesignSnapshotArtifact> toManifest(List<DesignArtifactJpaEntity> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) { throw new IllegalArgumentException("A snapshot needs an artifact manifest"); }
        return artifacts.stream().map(value -> {
            var file = value.storedFile();
            return new DesignSnapshotArtifact(value.getId(), value.getRole(), file.key(), file.originalName(),
                    file.contentType(), file.sizeBytes(), value.getChecksum());
        }).sorted(Comparator.comparing(value -> value.role().name())).toList();
    }

    private static DesignArtifactJpaEntity primary(List<DesignArtifactJpaEntity> artifacts) {
        return artifacts.stream().filter(value -> value.getRole() == com.stockflow.design.api.DesignArtifactRole.CUSTOMER_PREVIEW)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("A customer preview is required"));
    }
}
