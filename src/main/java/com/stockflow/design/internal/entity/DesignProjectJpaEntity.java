package com.stockflow.design.internal.entity;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.design.internal.domain.ProjectStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code design.design_project}: a customer's design project (kltn-docs 12 §4.9, §5.4; BR-17). Its designs name it
 * ({@code design_draft.project_id}); its library, rounds and quote requests are rows of their own.
 */
@Entity
@Table(name = "design_project", schema = "design")
public class DesignProjectJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;
    @Column(name = "owner_user_id", nullable = false, updatable = false)
    private UUID ownerUserId;
    @Column(name = "name", nullable = false, length = 200)
    private String name;
    @Column(name = "description", length = 2000)
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProjectStatus status;
    @Column(name = "archived_at")
    private Instant archivedAt;

    protected DesignProjectJpaEntity() {
    }

    public DesignProjectJpaEntity(UUID customerId, UUID ownerUserId, String name, String description) {
        super(Identifiers.newId());
        this.customerId = customerId;
        this.ownerUserId = ownerUserId;
        this.name = name;
        this.description = description;
        this.status = ProjectStatus.ACTIVE;
    }

    public void describe(String nextName, String nextDescription) {
        name = nextName;
        description = nextDescription;
    }

    /** Closed for good (BR-22, B42). */
    public void archive(Instant at) {
        status = ProjectStatus.ARCHIVED;
        archivedAt = at;
    }

    public UUID getCustomerId() { return customerId; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public ProjectStatus getStatus() { return status; }
    public Instant getArchivedAt() { return archivedAt; }
}
