package com.stockflow.design.internal.entity;

import com.stockflow.common.id.Identifiers;
import com.stockflow.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code design.design_review_round}: drafts of a project submitted together (kltn-docs 12 §4.10; BR-19, BR-20), one
 * item in the Sales Manager's queue. Each design in it is decided on its own
 * ({@link DesignReviewRoundDesignJpaEntity}); the round closes once every one is.
 */
@Entity
@Table(name = "design_review_round", schema = "design")
public class DesignReviewRoundJpaEntity extends BaseEntity {

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;
    @Column(name = "submitted_by", nullable = false, updatable = false)
    private UUID submittedBy;
    @Column(name = "submitted_at", nullable = false, updatable = false)
    private Instant submittedAt;
    @Column(name = "closed_at")
    private Instant closedAt;

    protected DesignReviewRoundJpaEntity() {
    }

    public DesignReviewRoundJpaEntity(UUID projectId, UUID submittedBy, Instant submittedAt) {
        super(Identifiers.newId());
        this.projectId = projectId;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
    }

    /** Every design in it has been approved or returned (BR-20). */
    public void close(Instant at) {
        closedAt = at;
    }

    public UUID getProjectId() { return projectId; }
    public UUID getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getClosedAt() { return closedAt; }
}
