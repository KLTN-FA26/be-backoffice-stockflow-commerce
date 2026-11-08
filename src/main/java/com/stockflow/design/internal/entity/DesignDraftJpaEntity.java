package com.stockflow.design.internal.entity;

import com.stockflow.common.persistence.BaseEntity;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.internal.domain.DesignStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A design (kltn-docs 12 §5.1): the customer's own, from start to approval. File uploads, layout saves, the submission
 * and the review all serialise through its row lock.
 *
 * <p>A design a customer started is drawn on one variant's customization template (BR-01): the variant, its SKU, the
 * template and its code are set when it is started and never change ({@code ck_design_draft_template}: all four or
 * none - a design staff started before customers designed has none, and is only read).</p>
 */
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
    @Column(name = "current_artifact_id")
    private UUID currentArtifactId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "current_artifacts", nullable = false, columnDefinition = "jsonb")
    private Map<DesignArtifactRole, UUID> currentArtifacts = new EnumMap<>(DesignArtifactRole.class);

    /** Who last decided the review, and the reason given when it was returned. */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;
    @Column(name = "review_notes", length = 2000)
    private String reviewNotes;
    @Column(name = "parent_snapshot_id", updatable = false)
    private UUID parentSnapshotId;
    @Column(name = "variant_id", updatable = false)
    private UUID variantId;
    @Column(name = "sku", length = 64, updatable = false)
    private String sku;
    @Column(name = "template_id", updatable = false)
    private UUID templateId;
    @Column(name = "template_code", length = 50, updatable = false)
    private String templateCode;
    @Column(name = "review_requested_at")
    private Instant reviewRequestedAt;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    @Column(name = "archived_at")
    private Instant archivedAt;

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

    /** The customer's own login that owns the design (BR-01); set once, when it is started. */
    public void ownedBy(UUID owner) { ownerUserId = owner; }

    /** The variant and template a customer's design is drawn on; called once, before the draft is first saved. */
    public void drawOn(UUID variant, String variantSku, UUID template, String code) {
        variantId = variant;
        sku = variantSku;
        templateId = template;
        templateCode = code;
    }

    public void derivedFrom(UUID snapshotId) { parentSnapshotId = snapshotId; }

    public void rename(String nextName) { name = nextName; }

    /** The current file of {@code role}; the customer preview is also the design's current artifact. */
    public void attachArtifact(DesignArtifactRole role, UUID artifactId) {
        currentArtifacts.put(role, artifactId);
        if (role == DesignArtifactRole.CUSTOMER_PREVIEW) {
            currentArtifactId = artifactId;
        }
    }

    /** Passed preflight (kltn-docs 12 §4.3 step 12): it waits for the Sales Manager, and nothing changes it (BR-07). */
    public void submitForReview(Instant at) {
        status = DesignStatus.PENDING_REVIEW;
        reviewRequestedAt = at;
    }

    /** Returned by the Sales Manager with a reason (BR-06): a draft again, for the customer to change. */
    public void returnToDraft(UUID reviewer, String reason, Instant at) {
        status = DesignStatus.DRAFT;
        reviewedBy = reviewer;
        reviewNotes = reason;
        reviewedAt = at;
    }

    /** Approved (BR-09): files, snapshot and the customer's SKU were issued in the same step; final (BR-08). */
    public void approve(UUID reviewer, Instant at) {
        status = DesignStatus.APPROVED;
        reviewedBy = reviewer;
        reviewNotes = null;
        reviewedAt = at;
    }

    /** A deleted draft (BR-16): kept with its history, seen by nobody. */
    public void archive(Instant at) {
        status = DesignStatus.ARCHIVED;
        archivedAt = at;
    }

    public UUID getCustomerId() { return customerId; }
    public UUID getProductId() { return productId; }
    public String getName() { return name; }
    public String getSpec() { return spec; }
    public DesignStatus getStatus() { return status; }
    public UUID getOwnerUserId() { return ownerUserId; }
    public UUID getCurrentArtifactId() { return currentArtifactId; }
    public Map<DesignArtifactRole, UUID> getCurrentArtifacts() { return Map.copyOf(currentArtifacts); }
    public UUID getReviewedBy() { return reviewedBy; }
    public String getReviewNotes() { return reviewNotes; }
    public UUID getParentSnapshotId() { return parentSnapshotId; }
    public UUID getVariantId() { return variantId; }
    public String getSku() { return sku; }
    public UUID getTemplateId() { return templateId; }
    public String getTemplateCode() { return templateCode; }
    public Instant getReviewRequestedAt() { return reviewRequestedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Instant getArchivedAt() { return archivedAt; }
}
