package com.stockflow.design.internal.entity;

import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;
import java.util.EnumMap;
import java.util.Map;
import com.stockflow.design.api.DesignArtifactRole;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Draft persistence; artifact uploads and review decisions serialize through the same row lock. */
@Entity
@Table(name = "design_draft", schema = "design")
public class DesignDraftJpaEntity extends BaseEntity {

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "spec", length = 4000)
    private String spec;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private DesignStatus status;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;
    @Column(name = "assigned_user_id")
    private UUID assignedUserId;
    @Column(name = "current_artifact_id")
    private UUID currentArtifactId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "current_artifacts", nullable = false, columnDefinition = "jsonb")
    private Map<DesignArtifactRole, UUID> currentArtifacts = new EnumMap<>(DesignArtifactRole.class);
    @Column(name = "preflight_passed", nullable = false)
    private boolean preflightPassed;

    @Column(name = "reviewer_user_id")
    private UUID reviewerUserId;
    @Column(name = "last_editor_user_id")
    private UUID lastEditorUserId;
    @Column(name = "review_notes", length = 2000)
    private String reviewNotes;
    @Column(name = "reviewed_by")
    private UUID reviewedBy;
    @Column(name = "parent_snapshot_id", updatable = false)
    private UUID parentSnapshotId;
    public UUID getParentSnapshotId() { return parentSnapshotId; }
    public void derivedFrom(UUID snapshotId) { parentSnapshotId = snapshotId; }

    public void assign(UUID owner, UUID designer, UUID reviewer) {
        ownerUserId = owner;
        assignedUserId = designer;
        reviewerUserId = reviewer;
    }
    public UUID getReviewerUserId() { return reviewerUserId; }
    public UUID getLastEditorUserId() { return lastEditorUserId; }
    public UUID getReviewedBy() { return reviewedBy; }
    public String getReviewNotes() { return reviewNotes; }
    public boolean isPreflightPassed() { return preflightPassed; }
    public void editedBy(UUID actor) { lastEditorUserId = actor; }
    public void revise(String nextSpec, UUID actor) {
        spec = nextSpec;
        lastEditorUserId = actor;
        preflightPassed = false;
        reviewedBy = null;
        reviewNotes = null;
        status = DesignStatus.DRAFT;
    }
    public void review(UUID actor, boolean passed, String notes) {
        reviewedBy = actor;
        preflightPassed = passed;
        reviewNotes = notes;
    }
    public void transition(DesignStatus next) { status = next; }

    public UUID getOwnerUserId() { return ownerUserId; }
    public UUID getAssignedUserId() { return assignedUserId; }
    public UUID getCurrentArtifactId() { return currentArtifactId; }
    public Map<DesignArtifactRole, UUID> getCurrentArtifacts() { return Map.copyOf(currentArtifacts); }

    /** Checking the previous file says nothing about this new revision. */
    public void attachArtifact(UUID artifactId) {
        attachArtifact(DesignArtifactRole.CUSTOMER_PREVIEW, artifactId);
    }

    public void attachArtifact(DesignArtifactRole role, UUID artifactId) {
        currentArtifacts.put(role, artifactId);
        if (role == DesignArtifactRole.CUSTOMER_PREVIEW) {
            currentArtifactId = artifactId;
        }
        preflightPassed = false;
        reviewedBy = null;
        reviewNotes = null;
    }

    protected DesignDraftJpaEntity() {
    }

    public DesignDraftJpaEntity(UUID id, UUID customerId, UUID productId, String name,
                                String spec, DesignStatus status) {
        super(id);
        this.customerId = customerId;
        this.productId = productId;
        this.name = name;
        this.spec = spec;
        this.status = status;
    }

    public UUID getCustomerId() { return customerId; }
    public UUID getProductId() { return productId; }
    public String getName() { return name; }
    public String getSpec() { return spec; }
    public DesignStatus getStatus() { return status; }
}
