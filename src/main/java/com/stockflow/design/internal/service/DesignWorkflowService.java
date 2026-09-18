package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.design.api.DesignService;
import com.stockflow.design.internal.domain.DesignReview;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.domain.DesignWorkflow;
import com.stockflow.design.internal.entity.DesignDecisionJpaEntity;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.entity.DesignSnapshotJpaEntity;
import com.stockflow.design.internal.repository.DesignArtifactJpaRepository;
import com.stockflow.design.internal.repository.DesignDecisionJpaRepository;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignSnapshotJpaRepository;
import com.stockflow.product.api.ProductService;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.stockflow.identity.api.IdentityService;
import com.stockflow.common.security.Role;
/** Coordinates row locks shared with artifact upload; reviewers supply explicit technical evidence. */
@Service
@Transactional
public class DesignWorkflowService {
    private final DesignDraftJpaRepository drafts;
    private final DesignArtifactJpaRepository artifacts;
    private final DesignSnapshotJpaRepository snapshots;
    private final ProductService products;
    private final Clock clock;
    private final DesignDecisionJpaRepository decisions;
    private final DesignService designs;
    private final IdentityService identities;

    public DesignWorkflowService(DesignDraftJpaRepository drafts, DesignArtifactJpaRepository artifacts,
            DesignSnapshotJpaRepository snapshots, ProductService products, Clock clock,
            DesignDecisionJpaRepository decisions,
            DesignService designs, IdentityService identities) {
        this.drafts = drafts; this.artifacts = artifacts; this.snapshots = snapshots;
        this.products = products; this.clock = clock;
        this.decisions = decisions; this.designs = designs;
        this.identities = identities;
    }

    public record DraftView(UUID id, UUID customerId, UUID productId, UUID ownerUserId,
            UUID assignedUserId, UUID reviewerUserId, String name, String spec, String status,
            UUID currentArtifactId, boolean technicalReviewPassed, String reviewNotes, long version, UUID parentSnapshotId) { }
    public record SnapshotView(UUID id, UUID draftId, UUID artifactId, String checksum,
            String spec, UUID confirmedBy, Instant confirmedAt, java.util.List<SnapshotArtifactView> artifacts) { }
    public record SnapshotArtifactView(UUID artifactId, com.stockflow.design.api.DesignArtifactRole role,
                                       String originalName, String contentType, long sizeBytes, String checksum) { }
    public record DecisionView(UUID actorId, UUID artifactId, String decision, String notes, long version, Instant at) { }

    @Transactional(readOnly = true)
    public Page<DecisionView> history(UUID id, UUID actor, Pageable page) {
        access(id, actor, false);
        return decisions.findByDraftIdOrderByCreatedAtDescIdDesc(id,
                com.stockflow.common.persistence.Pages.of(page.getPageNumber(), page.getPageSize())).map(d ->
                new DecisionView(d.getActorId(), d.getArtifactId(), d.getDecision(), d.getNotes(), d.getDraftVersion(), d.getCreatedAt()));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#id")
    public DraftView withdraw(UUID id, UUID actor, long version, String reason) {
        var draft = access(id, actor, true);
        DesignWorkflow.requireVersion(draft.getVersion(), version);
        if (!Objects.equals(actor, draft.getOwnerUserId()) && !Objects.equals(actor, draft.getAssignedUserId())) { forbidden(); }
        if (draft.getStatus() != DesignStatus.SUBMITTED) { conflict("No pending confirmation"); }
        decision(draft, actor, "WITHDRAWN", reason);
        draft.revise(draft.getSpec(), actor);
        return view(drafts.saveAndFlush(draft));
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "design")
    public DraftView create(UUID customerId, UUID productId, UUID owner, UUID designer,
                            UUID reviewer, String name, String spec, UUID actor) {
        authenticated(actor);
        if (owner == null || reviewer == null || reviewer.equals(owner) || reviewer.equals(designer)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Assign a customer and an independent reviewer");
        }
        requireAssignmentRoles(owner, designer, reviewer);
        products.findById(productId).orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        var draft = new DesignDraftJpaEntity(Identifiers.newId(), customerId, productId, name, spec, DesignStatus.DRAFT);
        draft.assign(owner, designer, reviewer);
        draft.editedBy(actor);
        return view(drafts.saveAndFlush(draft));
    }

    @Transactional(readOnly = true)
    public Page<DraftView> list(UUID actor, Pageable page) {
        authenticated(actor);
        return drafts.findForUser(actor, com.stockflow.common.persistence.Pages.of(page.getPageNumber(), page.getPageSize(),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt", "id")))
                .map(DesignWorkflowService::view);
    }

    @Transactional(readOnly = true)
    public DraftView get(UUID id, UUID actor) { return view(access(id, actor, false)); }

    @Transactional(readOnly = true)
    public SnapshotView snapshot(UUID id, UUID actor) {
        access(id, actor, false);
        return snapshotView(snapshots.findByDraftId(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#id")
    public DraftView revise(UUID id, UUID actor, long version, String spec) {
        var draft = access(id, actor, true);
        editable(draft, actor, version);
        draft.revise(spec, actor);
        decision(draft, actor, "SPECIFICATION_CHANGED", spec);
        return view(drafts.saveAndFlush(draft));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#id")
    public DraftView review(UUID id, UUID actor, long version, UUID artifactId, boolean passed, String notes) {
        var draft = access(id, actor, true);
        DesignWorkflow.requireVersion(draft.getVersion(), version);
        var decision = new DesignReview(artifactId, actor, passed, notes);
        DesignWorkflow.requireReview(draft.getStatus(), draft.getCurrentArtifactId(), artifactId,
                actor, draft.getOwnerUserId(), draft.getLastEditorUserId(), draft.getReviewerUserId());
        draft.review(actor, decision.passed(), decision.notes());
        decision(draft, actor, passed ? "TECHNICAL_ACCEPTED" : "TECHNICAL_REJECTED", notes);
        return view(drafts.saveAndFlush(draft));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#id")
    public DraftView submit(UUID id, UUID actor, long version) {
        var draft = access(id, actor, true);
        editable(draft, actor, version);
        if (!draft.isPreflightPassed() || draft.getCurrentArtifactId() == null) { conflict("Technical review is required"); }
        draft.transition(DesignStatus.SUBMITTED);
        decision(draft, actor, "SENT_TO_CUSTOMER", "Customer confirmation requested for the current artifact and specification");
        return view(drafts.saveAndFlush(draft));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#id")
    public DraftView requestChanges(UUID id, UUID actor, long version, String reason) {
        var draft = access(id, actor, true);
        DesignWorkflow.requireVersion(draft.getVersion(), version);
        if (!Objects.equals(actor, draft.getOwnerUserId())) { forbidden(); }
        if (draft.getStatus() != DesignStatus.SUBMITTED) { conflict("No pending confirmation"); }
        draft.revise(draft.getSpec(), actor);
        draft.review(null, false, reason);
        decision(draft, actor, "CUSTOMER_CHANGES_REQUESTED", reason);
        return view(drafts.saveAndFlush(draft));
    }

    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#id")
    public SnapshotView confirm(UUID id, UUID actor, long version, UUID artifactId) {
        var draft = access(id, actor, true);
        if (!Objects.equals(actor, draft.getOwnerUserId())) { forbidden(); }
        var existing = snapshots.findByDraftId(id);
        if (existing.isPresent() && Objects.equals(existing.get().getArtifactId(), artifactId)) {
            return snapshotView(existing.get());
        }
        DesignWorkflow.requireVersion(draft.getVersion(), version);
        DesignWorkflow.requireConfirmation(draft.getStatus(), draft.getCurrentArtifactId(), artifactId,
                draft.isPreflightPassed(), actor, draft.getOwnerUserId());
        var artifact = artifacts.findById(artifactId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!artifact.getDraftId().equals(id)) { conflict("Artifact belongs to another draft"); }
        var currentIds = new java.util.HashSet<>(draft.getCurrentArtifacts().values());
        if (currentIds.isEmpty()) { currentIds.add(artifactId); }
        var manifest = artifacts.findAllById(currentIds);
        if (manifest.size() != currentIds.size() || manifest.stream().anyMatch(value -> !value.getDraftId().equals(id))) {
            conflict("The current design bundle is incomplete");
        }
        var snapshot = snapshots.saveAndFlush(new DesignSnapshotJpaEntity(Identifiers.newId(), draft, manifest, actor, clock.instant()));
        designs.verifySnapshot(snapshot.getId(), draft.getCustomerId());
        decision(draft, actor, "CUSTOMER_CONFIRMED", snapshot.getId().toString());
        draft.transition(DesignStatus.APPROVED);
        drafts.saveAndFlush(draft);
        return snapshotView(snapshot);
    }

    @Auditable(action = AuditAction.CREATE, resourceType = "design", resourceId = "#id")
    public DraftView fork(UUID id, UUID actor) {
        var source = access(id, actor, false);
        if (!Objects.equals(actor, source.getOwnerUserId()) && !Objects.equals(actor, source.getAssignedUserId())) { forbidden(); }
        if (source.getStatus() != DesignStatus.APPROVED) { conflict("Only confirmed designs can be forked"); }
        // A new upload is intentional: the draft cannot mutate or reuse the old snapshot's object key.
        var draft = new DesignDraftJpaEntity(Identifiers.newId(), source.getCustomerId(), source.getProductId(),
                source.getName(), source.getSpec(), DesignStatus.DRAFT);
        draft.assign(source.getOwnerUserId(), source.getAssignedUserId(), source.getReviewerUserId());
        draft.editedBy(actor);
        draft.derivedFrom(snapshots.findByDraftId(id).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT)).getId());
        drafts.saveAndFlush(draft);
        decision(draft, actor, "DERIVED_FROM_SNAPSHOT", draft.getParentSnapshotId().toString());
        return view(drafts.saveAndFlush(draft));
    }

    /** Called only by the separately guarded design-administration endpoint. Ownership never changes here. */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#id")
    public DraftView assign(UUID id, UUID actor, long version, UUID designer, UUID reviewer) {
        authenticated(actor);
        var draft = drafts.lockById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        DesignWorkflow.requireVersion(draft.getVersion(), version);
        if (draft.getStatus() == DesignStatus.APPROVED || snapshots.existsByDraftId(id)) { conflict("Confirmed assignments are retained; create a new draft"); }
        if (reviewer == null || reviewer.equals(draft.getOwnerUserId()) || reviewer.equals(designer)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Assign an independent reviewer");
        }
        requireAssignmentRoles(draft.getOwnerUserId(), designer, reviewer);
        draft.assign(draft.getOwnerUserId(), designer, reviewer);
        draft.revise(draft.getSpec(), actor);
        decision(draft, actor, "ASSIGNMENT_CHANGED", "Designer=" + designer + "; reviewer=" + reviewer);
        return view(drafts.saveAndFlush(draft));
    }

    private DesignDraftJpaEntity access(UUID id, UUID actor, boolean lock) {
        authenticated(actor);
        var draft = (lock ? drafts.lockById(id) : drafts.findById(id))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        if (!Objects.equals(actor, draft.getOwnerUserId()) && !Objects.equals(actor, draft.getAssignedUserId())
                && !Objects.equals(actor, draft.getReviewerUserId())) { throw new BusinessException(ErrorCode.NOT_FOUND); }
        return draft;
    }
    private void editable(DesignDraftJpaEntity draft, UUID actor, long version) {
        DesignWorkflow.requireVersion(draft.getVersion(), version);
        if (!Objects.equals(actor, draft.getOwnerUserId()) && !Objects.equals(actor, draft.getAssignedUserId())) { forbidden(); }
        if (draft.getStatus() == DesignStatus.APPROVED || snapshots.existsByDraftId(draft.getId())) { conflict("Create a new draft for confirmed content"); }
        if (draft.getStatus() != DesignStatus.DRAFT) { conflict("Withdraw the pending confirmation before editing"); }
    }
    private static void authenticated(UUID actor) { if (actor == null) { throw new BusinessException(ErrorCode.UNAUTHORIZED); } }
    private void decision(DesignDraftJpaEntity draft, UUID actor, String action, String notes) {
        decisions.save(new DesignDecisionJpaEntity(draft, actor, action, notes));
    }
    private static void forbidden() { throw new BusinessException(ErrorCode.FORBIDDEN); }
    private static void conflict(String reason) { throw new BusinessException(ErrorCode.CONFLICT, reason); }
    private void requireAssignmentRoles(UUID owner, UUID designer, UUID reviewer) {
        if (!identities.isActiveUserWithAnyRole(owner, Role.CUSTOMER.authority())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Design owner must be an active customer account");
        }
        if (designer != null && !identities.isActiveUserWithAnyRole(designer,
                Role.SALES_STAFF.authority(), Role.ECOMMERCE_ADMIN.authority())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Assigned designer must be active sales staff or an e-commerce administrator");
        }
        if (!identities.isActiveUserWithAnyRole(reviewer, Role.QC_STAFF.authority())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "Reviewer must be an active QC staff member");
        }
    }
    private static DraftView view(DesignDraftJpaEntity d) {
        return new DraftView(d.getId(), d.getCustomerId(), d.getProductId(), d.getOwnerUserId(), d.getAssignedUserId(),
                d.getReviewerUserId(), d.getName(), d.getSpec(), d.getStatus().name(), d.getCurrentArtifactId(),
                d.isPreflightPassed(), d.getReviewNotes(), d.getVersion(), d.getParentSnapshotId());
    }
    private static SnapshotView snapshotView(DesignSnapshotJpaEntity s) {
        return new SnapshotView(s.getId(), s.getDraftId(), s.getArtifactId(), s.getChecksum(), s.getSpec(),
                s.getConfirmedBy(), s.getConfirmedAt(), s.getArtifactManifest().stream().map(value ->
                new SnapshotArtifactView(value.artifactId(), value.role(), value.originalName(), value.contentType(),
                        value.sizeBytes(), value.checksum())).toList());
    }
}
