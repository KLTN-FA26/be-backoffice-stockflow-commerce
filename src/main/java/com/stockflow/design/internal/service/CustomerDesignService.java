package com.stockflow.design.internal.service;

import com.stockflow.common.api.FieldError;
import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.json.CanonicalJson;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.design.internal.domain.CustomerDesignRules;
import com.stockflow.design.internal.domain.DesignDecisionType;
import com.stockflow.design.internal.domain.DesignDocumentRules;
import com.stockflow.design.internal.domain.DesignReadRights;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.domain.DesignWorkflow;
import com.stockflow.design.internal.entity.DesignDecisionJpaEntity;
import com.stockflow.design.internal.entity.DesignDocumentJpaEntity;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.repository.DesignAssetJpaRepository;
import com.stockflow.design.internal.repository.DesignDecisionJpaRepository;
import com.stockflow.design.internal.repository.DesignDocumentJpaRepository;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignSnapshotJpaRepository;
import com.stockflow.product.api.PrintArea;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.PrintConfigs;
import com.stockflow.product.api.ProductKind;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductStatus;
import com.stockflow.product.api.ProductSummary;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A design a customer makes on their own (kltn-docs 12 §4.1, §4.2, §4.7): started on one variant's customization
 * template, laid out in the storefront's studio and saved as a document, renamed and deleted while it is a draft, and
 * revised from an approved design.
 *
 * <p>Every write takes the design's row lock first and checks the version the customer saw (BR-11); a layout save
 * moves the version as any other write does, though it rewrites only the layout's row. Only the owner reaches a design
 * here; anyone else, and everyone once it is archived, is told it does not exist (BR-03, B38).</p>
 */
@Service
@Transactional
public class CustomerDesignService {

    /** As {@code ck_design_document_size}, and as the request body limit of the layout save. */
    static final int MAX_DOCUMENT_BYTES = 1_000_000;

    /** JavaScript's largest exact integer: a version above it would not survive the browser. */
    private static final double MAX_VERSION = 9_007_199_254_740_991d;

    private final DesignDraftJpaRepository drafts;
    private final DesignDocumentJpaRepository documents;
    private final DesignAssetJpaRepository assets;
    private final DesignDecisionJpaRepository decisions;
    private final DesignSnapshotJpaRepository snapshots;
    private final CustomerService customers;
    private final ProductService products;
    private final PrintConfigs printConfigs;
    private final DraftViews views;
    private final DesignQuoteRequestService quoteRequests;
    private final EntityManager entityManager;
    private final Clock clock;

    public CustomerDesignService(DesignDraftJpaRepository drafts, DesignDocumentJpaRepository documents,
                                 DesignAssetJpaRepository assets,
                                 DesignDecisionJpaRepository decisions, DesignSnapshotJpaRepository snapshots,
                                 CustomerService customers, ProductService products, PrintConfigs printConfigs,
                                 DraftViews views, DesignQuoteRequestService quoteRequests,
                                 EntityManager entityManager, Clock clock) {
        this.drafts = drafts;
        this.documents = documents;
        this.assets = assets;
        this.decisions = decisions;
        this.snapshots = snapshots;
        this.customers = customers;
        this.products = products;
        this.printConfigs = printConfigs;
        this.views = views;
        this.quoteRequests = quoteRequests;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** What a customer starts a design from: a product, one of its variants and that variant's template, and a name. */
    public record StartDesign(UUID productId, String sku, String templateCode, String name) {
    }

    /** A design's layout as stored: canonical JSON, its checksum, and the design version it was read at. */
    public record DocumentView(UUID designId, long version, String document, String checksum, Instant updatedAt) {
    }

    /**
     * The customer signed in starts a draft (§4.1, BR-01, BR-02): on an ACTIVE variant of an approved or published
     * CUSTOMIZABLE product, drawn on the variant's active template, owned by them and stored with the empty layout
     * the studio starts from. A customer holds at most {@value CustomerDesignRules#MAX_DRAFTS} drafts (B37).
     */
    @Auditable(action = AuditAction.CREATE, resourceType = "design")
    public DesignWorkflowService.DraftView start(UUID actor, StartDesign command) {
        CustomerSummary customer = activeCustomerOf(actor);
        requireDesignable(command.productId());
        PrintConfig template = printConfigs.activeOfProduct(command.productId()).stream()
                .filter(t -> t.sku().equals(command.sku()) && t.code().equals(command.templateCode()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRINT_CONFIG_NOT_FOUND,
                        "This variant has no active template " + command.templateCode()));
        requireRoomForDraft(customer.customerId());

        var draft = new DesignDraftJpaEntity(Identifiers.newId(), customer.customerId(), command.productId(),
                command.name().strip(), null, DesignStatus.DRAFT);
        draft.ownedBy(actor);
        draft.drawOn(template.variantId(), template.sku(), template.templateId(), template.code());
        drafts.saveAndFlush(draft);
        String layout = CanonicalJson.canonical(DesignDocumentRules.empty(frame(template, List.of())));
        documents.save(new DesignDocumentJpaEntity(draft.getId(), layout, CanonicalJson.sha256Hex(layout),
                clock.instant()));
        decide(draft, actor, DesignDecisionType.CREATED, "SKU " + template.sku() + ", template " + template.code());
        return views.of(draft);
    }

    /** Renamed while it is a draft (§4.1). */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#id")
    public DesignWorkflowService.DraftView rename(UUID id, UUID actor, long version, String name) {
        DesignDraftJpaEntity draft = lockOwnDraft(id, actor, version);
        draft.rename(name.strip());
        return views.of(drafts.saveAndFlush(draft));
    }

    /**
     * Deleted while it is a draft (BR-16): archived, never removed, so its history stays. A quote request still open on
     * it - a design returned after its request - closes with it (B40).
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#id")
    public DesignWorkflowService.DraftView archive(UUID id, UUID actor, long version) {
        DesignDraftJpaEntity draft = lockOwnDraft(id, actor, version);
        Instant now = clock.instant();
        draft.archive(now);
        drafts.saveAndFlush(draft);
        quoteRequests.closeOpen(id, now);
        decide(draft, actor, DesignDecisionType.ARCHIVED, "Deleted by the customer");
        return views.of(draft);
    }

    /** The design's layout, at the design's current version; to whoever reads the design (plan D7). */
    @Transactional(readOnly = true)
    public DocumentView document(UUID id, UUID actor, DesignReadRights rights) {
        DesignDraftJpaEntity draft = drafts.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        CustomerDesignRules.requireReader(actor, draft.getOwnerUserId(), draft.getStatus(),
                draft.getTemplateId() != null, rights, () -> drafts.hasOpenQuoteRequest(id));
        DesignDocumentJpaEntity layout = documents.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "This design has no layout"));
        return new DocumentView(id, draft.getVersion(), layout.getDocument(), layout.getChecksum(),
                layout.getUpdatedAt());
    }

    /**
     * Saves a layout (§4.2 step 7, BR-11). {@code body} is the request as sent - {@code {"version": n, "document":
     * {...}}} - read here rather than by the web layer, so that what is checked and hashed is exactly what the
     * customer's browser hashed: a duplicate member is refused instead of silently dropped, and every number goes
     * through the double the browser holds it as. The layout is stored in canonical form (RFC 8785) with its SHA-256,
     * which the files a customer submits are later bound to (BR-08, BR-10).
     *
     * <p>A layout is checked only as far as {@link DesignDocumentRules} says; whether it prints is preflight's
     * question, at submission (BR-05). The template it is checked against is the design's own, as it is now, even if
     * it was deactivated since the design was started (B39).</p>
     */
    public DocumentView saveDocument(UUID id, UUID actor, String body) {
        Map<?, ?> request = requestObject(body);
        long version = version(request.get("version"));
        if (!(request.get("document") instanceof Map<?, ?> document)) {
            throw invalid("document", "document must be a JSON object", "NotNull");
        }
        DesignDraftJpaEntity draft = lockOwnDraft(id, actor, version);
        if (draft.getTemplateId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "This design was not started on a template");
        }
        PrintConfig template = printConfigs.byId(draft.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRINT_CONFIG_NOT_FOUND));
        String problem = DesignDocumentRules.problem(document,
                frame(template, assets.idsOf(id).stream().map(UUID::toString).toList()));
        if (problem != null) {
            throw invalid("document", problem, "DesignDocument");
        }
        String layout = canonical(document);
        String checksum = CanonicalJson.sha256Hex(layout);
        Instant at = clock.instant();
        DesignDocumentJpaEntity stored = documents.findById(id).orElse(null);
        if (stored == null) {
            stored = new DesignDocumentJpaEntity(id, layout, checksum, at);
        } else {
            stored.replace(layout, checksum, at);
        }
        documents.saveAndFlush(stored);
        // Only the layout's row changed; the design's version must still move, or a second tab holding the same
        // version would overwrite this save.
        entityManager.lock(draft, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        return new DocumentView(id, draft.getVersion(), layout, checksum, at);
    }

    /**
     * The template the design is drawn on, as {@link TemplateShapes} writes it: for an approved design the template
     * frozen in its snapshot, so it reopens exactly as approved (BR-08); otherwise the template as it is now, whether
     * or not it is still active (B39).
     */
    @Transactional(readOnly = true)
    public String template(UUID id, UUID actor, DesignReadRights rights) {
        DesignDraftJpaEntity draft = drafts.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        CustomerDesignRules.requireReader(actor, draft.getOwnerUserId(), draft.getStatus(),
                draft.getTemplateId() != null, rights, () -> drafts.hasOpenQuoteRequest(id));
        if (draft.getTemplateId() == null) {
            throw new BusinessException(ErrorCode.PRINT_CONFIG_NOT_FOUND, "This design was not started on a template");
        }
        if (draft.getStatus() == DesignStatus.APPROVED) {
            var frozen = snapshots.findByDraftId(id).map(snapshot -> snapshot.getTemplate());
            if (frozen.isPresent() && frozen.get() != null) {
                return frozen.get();
            }
        }
        return TemplateShapes.json(printConfigs.byId(draft.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRINT_CONFIG_NOT_FOUND)));
    }

    /**
     * A revision of an approved customer design (§4.7): a new draft of the same customer, product, variant and
     * template, with the approved layout and the same images (same ids, same stored files), naming the approved
     * snapshot as its source. Counted against the draft limit, and refused when the variant or its template can no
     * longer be designed on (BR-02) - the copied layout is drawn on that template and nothing else.
     *
     * <p>The layout is the approved design's own: once approved it cannot change (BR-08), so it is what was approved.
     * </p>
     *
     * <p>Reached through {@link DesignWorkflowService#fork}, which audits the request.</p>
     */
    public DesignWorkflowService.DraftView revise(UUID sourceId, UUID actor) {
        DesignDraftJpaEntity source = drafts.findById(sourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        CustomerDesignRules.requireOwner(actor, source.getOwnerUserId(), source.getStatus());
        if (source.getStatus() != DesignStatus.APPROVED) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only an approved design can be revised");
        }
        UUID snapshotId = snapshots.findByDraftId(sourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "The approved design has no snapshot"))
                .getId();
        CustomerSummary customer = activeCustomerOf(actor);
        if (!customer.customerId().equals(source.getCustomerId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        requireDesignable(source.getProductId());
        PrintConfig template = printConfigs.activeOfProduct(source.getProductId()).stream()
                .filter(t -> t.templateId().equals(source.getTemplateId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.PRINT_CONFIG_NOT_FOUND,
                        "The variant or its template can no longer be designed on; start a new design"));
        requireRoomForDraft(customer.customerId());

        var draft = new DesignDraftJpaEntity(Identifiers.newId(), source.getCustomerId(), source.getProductId(),
                source.getName(), source.getSpec(), DesignStatus.DRAFT);
        draft.ownedBy(actor);
        draft.drawOn(template.variantId(), template.sku(), template.templateId(), template.code());
        draft.derivedFrom(snapshotId);
        drafts.saveAndFlush(draft);
        assets.copyAssets(sourceId, draft.getId());
        DesignDocumentJpaEntity layout = documents.findById(sourceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "The approved design has no layout"));
        documents.save(new DesignDocumentJpaEntity(draft.getId(), layout.getDocument(), layout.getChecksum(),
                clock.instant()));
        decide(draft, actor, DesignDecisionType.DERIVED_FROM_SNAPSHOT, snapshotId.toString());
        return views.of(draft);
    }

    private DesignDraftJpaEntity lockOwnDraft(UUID id, UUID actor, long version) {
        DesignDraftJpaEntity draft = drafts.lockById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // The lock read may hand back a copy this transaction loaded before: read the row as it is now.
        entityManager.refresh(draft);
        CustomerDesignRules.requireOwner(actor, draft.getOwnerUserId(), draft.getStatus());
        DesignWorkflow.requireVersion(draft.getVersion(), version);
        CustomerDesignRules.requireDraft(draft.getStatus());
        return draft;
    }

    /** The customer whose own login {@code actor} is, while the customer may take on new work (BR-01). */
    private CustomerSummary activeCustomerOf(UUID actor) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        CustomerSummary customer = customers.findByUserId(actor).orElseThrow(() ->
                new BusinessException(ErrorCode.FORBIDDEN, "Only a customer designs on their own account"));
        if (!"ACTIVE".equals(customer.status())) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_ACTIVE,
                    "The customer is " + customer.status() + "; only an active customer starts a design");
        }
        return customer;
    }

    /** An approved or published CUSTOMIZABLE product (BR-02); one in any other state does not exist to a customer. */
    private void requireDesignable(UUID productId) {
        ProductSummary product = products.findById(productId)
                .filter(p -> p.status() == ProductStatus.APPROVED || p.status() == ProductStatus.PUBLISHED)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (product.kind() != ProductKind.CUSTOMIZABLE) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_CUSTOMIZABLE);
        }
    }

    /**
     * Room for one more draft of {@code customerId} (B37), counted under a lock per customer held until the caller's
     * transaction ends, so two drafts started at once cannot both take the last place. Public for the staff
     * workflow's revision, which makes a customer's draft too.
     */
    public void requireRoomForDraft(UUID customerId) {
        drafts.lockCustomerDrafts("design-drafts:" + customerId);
        CustomerDesignRules.requireRoomForDraft(drafts.countByCustomerIdAndStatus(customerId, DesignStatus.DRAFT));
    }

    private void decide(DesignDraftJpaEntity draft, UUID actor, DesignDecisionType type, String notes) {
        decisions.save(new DesignDecisionJpaEntity(draft, actor, type.name(), notes));
    }

    private static DesignDocumentRules.Frame frame(PrintConfig template, List<String> assetIds) {
        return new DesignDocumentRules.Frame(template.code(), template.sku(),
                template.printAreas().stream().map(PrintArea::code).toList(), new HashSet<>(assetIds));
    }

    private static Map<?, ?> requestObject(String body) {
        Object parsed;
        try {
            parsed = CanonicalJson.parse(body);
        } catch (CanonicalJson.InvalidJsonException ex) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, ex.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> request)) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "The request must be a JSON object");
        }
        return request;
    }

    private static long version(Object value) {
        if (!(value instanceof Number number) || number.doubleValue() < 0 || number.doubleValue() > MAX_VERSION
                || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw invalid("version", "version must be a whole number of 0 or more", "PositiveOrZero");
        }
        return (long) number.doubleValue();
    }

    /** The canonical text, refused when it is larger than the table holds: canonical form can outgrow the request. */
    private static String canonical(Map<?, ?> document) {
        String layout = CanonicalJson.canonical(document);
        if (layout.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            throw new BusinessException(ErrorCode.REQUEST_BODY_TOO_LARGE,
                    "The design document is larger than " + MAX_DOCUMENT_BYTES + " bytes");
        }
        return layout;
    }

    private static BusinessException invalid(String field, String message, String code) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message, List.of(new FieldError(field, message, code)));
    }
}
