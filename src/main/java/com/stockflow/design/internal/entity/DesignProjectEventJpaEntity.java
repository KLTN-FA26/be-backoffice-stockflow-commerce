package com.stockflow.design.internal.entity;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * {@code design.design_project_event}: one step of a design project's history (kltn-docs 12 §5.4, BR-13).
 * Append-only in the table ({@code immutable_design_project_event}): every column is insert-only here.
 */
@Entity
@Table(name = "design_project_event", schema = "design")
public class DesignProjectEventJpaEntity extends BaseEntity {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;
    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;
    @Column(name = "event", nullable = false, updatable = false, length = 40)
    private String event;
    @Column(name = "notes", nullable = false, updatable = false, length = 4000)
    private String notes;

    protected DesignProjectEventJpaEntity() {
    }

    public DesignProjectEventJpaEntity(UUID projectId, UUID actorId, String event, String notes) {
        super(Identifiers.newId());
        this.projectId = projectId;
        this.actorId = actorId;
        this.event = event;
        this.notes = notes.length() <= 4000 ? notes : notes.substring(0, 3997) + "...";
    }

    public UUID getProjectId() { return projectId; }
    public UUID getActorId() { return actorId; }
    public String getEvent() { return event; }
    public String getNotes() { return notes; }
}
