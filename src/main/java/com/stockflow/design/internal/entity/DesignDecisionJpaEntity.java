package com.stockflow.design.internal.entity;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** Append-only business evidence, including unsuccessful technical reviews and customer feedback. */
@Entity
@Table(name = "design_decision", schema = "design")
public class DesignDecisionJpaEntity extends BaseEntity {
    @Column(name = "draft_id", nullable = false, updatable = false)
    private UUID draftId;
    @Column(name = "artifact_id", updatable = false)
    private UUID artifactId;
    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;
    @Column(name = "decision", nullable = false, updatable = false, length = 40)
    private String decision;
    @Column(name = "notes", nullable = false, updatable = false, length = 4000)
    private String notes;
    @Column(name = "draft_version", nullable = false, updatable = false)
    private long draftVersion;
    protected DesignDecisionJpaEntity() { }
    public DesignDecisionJpaEntity(DesignDraftJpaEntity draft, UUID actor, String decision, String notes) {
        super(Identifiers.newId()); this.draftId = draft.getId(); this.artifactId = draft.getCurrentArtifactId();
        this.actorId = actor; this.decision = decision; this.notes = notes; this.draftVersion = draft.getVersion();
    }
    public UUID getArtifactId() { return artifactId; }
    public UUID getActorId() { return actorId; }
    public String getDecision() { return decision; }
    public String getNotes() { return notes; }
    public long getDraftVersion() { return draftVersion; }
}
