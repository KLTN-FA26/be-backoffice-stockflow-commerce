package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.internal.domain.DesignReadRights;
import com.stockflow.design.internal.domain.CustomerDesignRules;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.entity.DesignSnapshotJpaEntity;
import com.stockflow.design.internal.repository.DesignDecisionJpaRepository;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignSnapshotJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading a design - the list of a user's own, one design, its history and its approved snapshot - and revising an
 * approved one. Who reads is {@link CustomerDesignRules#requireReader}: the owner, a reviewer while a customer's
 * design waits for review, and the sales staff while a quote request on it is open and it waits for review or is
 * approved (BR-03, plan D7). Starting, laying out, submitting and deciding a design are
 * {@link CustomerDesignService}, {@link DesignSubmissionService} and {@link DesignReviewService}.
 */
@Service
@Transactional
public class DesignWorkflowService {

    private final DesignDraftJpaRepository drafts;
    private final DesignSnapshotJpaRepository snapshots;
    private final DesignDecisionJpaRepository decisions;
    private final DraftViews views;
    private final CustomerDesignService customerDesigns;
    private final com.stockflow.product.api.CustomerSkus customerSkus;

    public DesignWorkflowService(DesignDraftJpaRepository drafts, DesignSnapshotJpaRepository snapshots,
                                 DesignDecisionJpaRepository decisions, DraftViews views,
                                 CustomerDesignService customerDesigns,
                                 com.stockflow.product.api.CustomerSkus customerSkus) {
        this.drafts = drafts;
        this.snapshots = snapshots;
        this.decisions = decisions;
        this.views = views;
        this.customerDesigns = customerDesigns;
        this.customerSkus = customerSkus;
    }

    /**
     * A design as the API shows it. {@code productSlug} is null for a product since removed; {@code sku} and
     * {@code templateCode} are null for a design staff started before customers designed; {@code reviewNotes} is the
     * Sales Manager's reason when it was returned; {@code updatedAt} is when the design or its layout last changed.
     */
    public record DraftView(UUID id, UUID customerId, UUID productId, UUID ownerUserId, String name, String spec,
            String status, UUID currentArtifactId, UUID reviewedBy, String reviewNotes, long version,
            UUID parentSnapshotId, String productSlug, String sku, String templateCode, Instant reviewRequestedAt,
            Instant reviewedAt, Instant updatedAt, UUID projectId) { }

    /**
     * An approved design's snapshot. {@code document}, {@code documentChecksum}, {@code assets} and
     * {@code customerSku} are null for a snapshot of the staff workflow.
     */
    public record SnapshotView(UUID id, UUID draftId, UUID artifactId, String checksum, String spec,
            UUID confirmedBy, Instant confirmedAt, List<SnapshotArtifactView> artifacts, String document,
            String documentChecksum, List<SnapshotAssetView> assets, String customerSku) { }

    public record SnapshotArtifactView(UUID artifactId, DesignArtifactRole role, String originalName,
                                       String contentType, long sizeBytes, String checksum) { }

    public record SnapshotAssetView(UUID assetId, String originalName, String contentType, long sizeBytes,
                                    String checksum, int widthPx, int heightPx) { }

    public record DecisionView(UUID actorId, UUID artifactId, String decision, String notes, long version,
                               Instant at) { }

    /** What the design list shows when no status is asked for: every design but an archived one (§4.2 step 8). */
    private static final List<DesignStatus> LISTED = List.of(DesignStatus.DRAFT, DesignStatus.PENDING_REVIEW,
            DesignStatus.APPROVED);

    /**
     * The user's own designs, newest first: in {@code status} when one is given (in any case; blank is none),
     * otherwise every one but an archived design. An archived design is listed for nobody (BR-16), so asking for
     * {@code ARCHIVED} - or for a status that does not exist - is a 400.
     */
    @Transactional(readOnly = true)
    public Page<DraftView> list(UUID actor, String status, Pageable page) {
        authenticated(actor);
        Page<DesignDraftJpaEntity> found = drafts.findOwnedBy(actor, statuses(status), Pages.of(page.getPageNumber(),
                page.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return new PageImpl<>(views.of(found.getContent()), found.getPageable(), found.getTotalElements());
    }

    private static List<DesignStatus> statuses(String status) {
        if (status == null || status.isBlank()) {
            return LISTED;
        }
        return LISTED.stream().filter(s -> s.name().equalsIgnoreCase(status.strip())).findFirst().map(List::of)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNSUPPORTED_PARAMETER,
                        "status must be one of " + LISTED));
    }

    @Transactional(readOnly = true)
    public DraftView get(UUID id, UUID actor, DesignReadRights rights) {
        return views.of(readable(id, actor, rights));
    }

    @Transactional(readOnly = true)
    public Page<DecisionView> history(UUID id, UUID actor, DesignReadRights rights, Pageable page) {
        readable(id, actor, rights);
        return decisions.findByDraftIdOrderByCreatedAtDescIdDesc(id, Pages.of(page.getPageNumber(),
                page.getPageSize())).map(d -> new DecisionView(d.getActorId(), d.getArtifactId(), d.getDecision(),
                d.getNotes(), d.getDraftVersion(), d.getCreatedAt()));
    }

    /** The approved design's snapshot, to its owner (approved designs are looked up by staff through snapshots). */
    @Transactional(readOnly = true)
    public SnapshotView snapshot(UUID id, UUID actor) {
        readable(id, actor, DesignReadRights.NONE);
        var snapshot = snapshots.findByDraftId(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        return snapshotView(snapshot, customerSkus.ofSnapshot(snapshot.getId())
                .map(com.stockflow.product.api.CustomerSku::sku).orElse(null));
    }

    /**
     * A revision of an approved design (kltn-docs 12 §4.7). A customer's design is revised by
     * {@link CustomerDesignService#revise}: same variant and template, the approved layout and images copied, counted
     * against the draft limit. A design staff started has no layout to copy: the revision is a new draft of the same
     * customer and product naming the same snapshot, counted against the limit too (B37).
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "design", resourceId = "#id")
    public DraftView fork(UUID id, UUID actor) {
        var source = readable(id, actor, DesignReadRights.NONE);
        if (source.getTemplateId() != null) {
            return customerDesigns.revise(id, actor);
        }
        if (source.getStatus() != DesignStatus.APPROVED) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only an approved design can be revised");
        }
        UUID snapshotId = snapshots.findByDraftId(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "The approved design has no snapshot"))
                .getId();
        customerDesigns.requireRoomForDraft(source.getCustomerId());
        var draft = new DesignDraftJpaEntity(com.stockflow.common.id.Identifiers.newId(), source.getCustomerId(),
                source.getProductId(), source.getName(), source.getSpec(), DesignStatus.DRAFT);
        draft.ownedBy(source.getOwnerUserId());
        draft.derivedFrom(snapshotId);
        drafts.saveAndFlush(draft);
        decisions.save(new com.stockflow.design.internal.entity.DesignDecisionJpaEntity(draft, actor,
                com.stockflow.design.internal.domain.DesignDecisionType.DERIVED_FROM_SNAPSHOT.name(),
                snapshotId.toString()));
        return views.of(draft);
    }

    private DesignDraftJpaEntity readable(UUID id, UUID actor, DesignReadRights rights) {
        authenticated(actor);
        var draft = drafts.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        CustomerDesignRules.requireReader(actor, draft.getOwnerUserId(), draft.getStatus(),
                draft.getTemplateId() != null, rights, () -> drafts.hasOpenQuoteRequest(id));
        return draft;
    }

    private static void authenticated(UUID actor) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    static SnapshotView snapshotView(DesignSnapshotJpaEntity s, String customerSku) {
        return new SnapshotView(s.getId(), s.getDraftId(), s.getArtifactId(), s.getChecksum(), s.getSpec(),
                s.getConfirmedBy(), s.getConfirmedAt(), s.getArtifactManifest().stream().map(value ->
                new SnapshotArtifactView(value.artifactId(), value.role(), value.originalName(), value.contentType(),
                        value.sizeBytes(), value.checksum())).toList(), s.getDocument(), s.getDocumentChecksum(),
                s.getAssets() == null ? null : s.getAssets().stream().map(asset -> new SnapshotAssetView(
                        asset.assetId(), asset.originalName(), asset.contentType(), asset.sizeBytes(),
                        asset.checksum(), asset.widthPx(), asset.heightPx())).toList(),
                customerSku);
    }
}
