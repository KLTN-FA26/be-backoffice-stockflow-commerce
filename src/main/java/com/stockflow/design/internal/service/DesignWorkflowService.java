package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.api.FieldError;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.api.DesignService;
import com.stockflow.design.internal.domain.DesignReview;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.domain.DesignWorkflow;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.design.internal.entity.DesignArtifactJpaEntity;
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
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
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
    private final CustomerService customers;
    private final com.stockflow.common.storage.FileTransfers files;
    private final DesignPreflight preflight;
    private final TransactionOperations tx;

    public DesignWorkflowService(DesignDraftJpaRepository drafts, DesignArtifactJpaRepository artifacts,
            DesignSnapshotJpaRepository snapshots, ProductService products, Clock clock,
            DesignDecisionJpaRepository decisions,
            DesignService designs, IdentityService identities, CustomerService customers,
            com.stockflow.common.storage.FileTransfers files, DesignPreflight preflight, TransactionOperations tx) {
        this.drafts = drafts; this.artifacts = artifacts; this.snapshots = snapshots;
        this.products = products; this.clock = clock;
        this.decisions = decisions; this.designs = designs;
        this.identities = identities;
        this.customers = customers;
        this.files = files;
        this.preflight = preflight;
        this.tx = tx;
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
        requireOwnerBelongsToCustomer(owner, customerId);
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

    /**
     * The independent reviewer's technical decision on the current bundle. Passing it (BR-DSG-001) first needs every
     * file of the bundle to pass the machine preflight - the preview being reviewed and the PRINT_READY file the print
     * shop is sent alike: a file the machine already refuses does not get a person's approval.
     *
     * <p>Not transactional itself. The cheap checks - version, reviewer, status, current file - run first in a short
     * read, so nobody else makes the server read a print file; the files are then read with no transaction held (a
     * 50 MB print file must hold neither a pooled connection nor the draft's lock); the decision is recorded in a short
     * transaction that locks the draft and checks again. A file uploaded meanwhile voids the review and bumps the
     * version, which that second check refuses.</p>
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#id")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DraftView review(UUID id, UUID actor, long version, UUID artifactId, boolean passed, String notes) {
        var decision = new DesignReview(artifactId, actor, passed, notes);
        if (decision.passed()) {
            List<StoredFile> bundle = tx.execute(status -> {
                var draft = access(id, actor, false);
                requireReviewable(draft, version, artifactId, actor);
                return bundleFiles(draft);
            });
            for (StoredFile file : bundle) {
                var report = preflight.inspect(file.key(), file.contentType());
                if (!report.passed()) {
                    conflict("Preflight failed for " + file.originalName() + ": " + report.blockingSummary());
                }
            }
        }
        return tx.execute(status -> {
            var draft = access(id, actor, true);
            requireReviewable(draft, version, artifactId, actor);
            draft.review(actor, decision.passed(), decision.notes());
            decision(draft, actor, decision.passed() ? "TECHNICAL_ACCEPTED" : "TECHNICAL_REJECTED", notes);
            return view(drafts.saveAndFlush(draft));
        });
    }

    private static void requireReviewable(DesignDraftJpaEntity draft, long version, UUID artifactId, UUID actor) {
        DesignWorkflow.requireVersion(draft.getVersion(), version);
        DesignWorkflow.requireReview(draft.getStatus(), draft.getCurrentArtifactId(), artifactId,
                actor, draft.getOwnerUserId(), draft.getLastEditorUserId(), draft.getReviewerUserId());
    }

    /** Every file of the draft's current bundle, one per role: what a confirmation would freeze and prepress print. */
    private List<StoredFile> bundleFiles(DesignDraftJpaEntity draft) {
        var ids = new java.util.HashSet<>(draft.getCurrentArtifacts().values());
        var bundle = artifacts.findAllById(ids).stream().filter(file -> file.getDraftId().equals(draft.getId())).toList();
        if (bundle.size() != ids.size()) {
            conflict("The current design bundle is incomplete");
        }
        return bundle.stream().map(DesignArtifactJpaEntity::storedFile).toList();
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
        // What the customer approves must be what is stored: every file of the bundle, by the digest the
        // store recorded, before the snapshot can be referenced by an order.
        if (!designs.verifyForFulfillment(snapshot.getId(), snapshot.getChecksum()).matching()) {
            conflict("Stored artifact differs from the confirmed snapshot");
        }
        // From here what was approved is what is printed: once this commits, no file of the bundle can be
        // deleted or overwritten for the confirmed-design retention period (S3 Object Lock).
        snapshot.getArtifactManifest().forEach(item -> files.retainAfterCommit(item.storageKey(),
                com.stockflow.common.storage.RecordRetention.CONFIRMED_DESIGN));
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
    /**
     * The owner confirms the design and the order later verifies it against {@code customerId}, so the two
     * must name the same company. A customer has exactly one login ({@code customer.customer.user_id} is
     * unique), hence the owner's own profile has to be the customer the request names.
     */
    private void requireOwnerBelongsToCustomer(UUID owner, UUID customerId) {
        boolean belongs = customerId != null
                && customers.findByUserId(owner).filter(c -> customerId.equals(c.customerId())).isPresent();
        if (!belongs) {
            throw new BusinessException(ErrorCode.DESIGN_OWNER_CUSTOMER_MISMATCH,
                    ErrorCode.DESIGN_OWNER_CUSTOMER_MISMATCH.defaultMessage(),
                    java.util.List.of(new FieldError("ownerUserId", ErrorCode.DESIGN_OWNER_CUSTOMER_MISMATCH.defaultMessage(),
                            ErrorCode.DESIGN_OWNER_CUSTOMER_MISMATCH.name())));
        }
    }
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
