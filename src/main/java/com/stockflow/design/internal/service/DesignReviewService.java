package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.id.Identifiers;
import com.stockflow.common.json.CanonicalJson;
import com.stockflow.common.persistence.Pages;
import com.stockflow.common.storage.Checksums;
import com.stockflow.common.storage.FileCategory;
import com.stockflow.common.storage.FileStorage;
import com.stockflow.common.storage.FileTransfers;
import com.stockflow.common.storage.FileUpload;
import com.stockflow.common.storage.PreparedFiles;
import com.stockflow.common.storage.RecordRetention;
import com.stockflow.common.storage.StorageException;
import com.stockflow.common.storage.StoredFile;
import com.stockflow.contracts.DesignReviewed;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.internal.domain.DesignDecisionType;
import com.stockflow.design.internal.domain.DesignSnapshotAsset;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.domain.DesignWorkflow;
import com.stockflow.design.internal.entity.DesignArtifactJpaEntity;
import com.stockflow.design.internal.entity.DesignDecisionJpaEntity;
import com.stockflow.design.internal.entity.DesignDocumentJpaEntity;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.entity.DesignSnapshotJpaEntity;
import com.stockflow.design.internal.repository.DesignArtifactJpaRepository;
import com.stockflow.design.internal.repository.DesignAssetJpaRepository;
import com.stockflow.design.internal.repository.DesignDecisionJpaRepository;
import com.stockflow.design.internal.repository.DesignDocumentJpaRepository;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignSnapshotJpaRepository;
import com.stockflow.inventory.api.UomConversion;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.CustomerSku;
import com.stockflow.product.api.CustomerSkus;
import com.stockflow.product.api.IssueCustomerSkuCommand;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.PrintConfigs;
import com.stockflow.product.api.ProductService;
import com.stockflow.product.api.ProductSummary;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * The Sales Manager's side of a customer's design (kltn-docs 12 §4.4, §4.5): the queue of designs submitted alone, and
 * the decision on one - also on one in a project's review round, which the decision counts towards closing (§4.10,
 * {@link DesignReviewRounds}). Who may decide is the permission on the endpoint ({@code design-designs:APPROVE}; BR-06), not who
 * owns the design.
 *
 * <h2>Approving is one step (BR-09)</h2>
 * <p>The files are checked, the print file stamped for the press, the production instruction drawn, the snapshot
 * written with the layout and the images, every file and image retained, and the customer's SKU issued - or none of
 * it: if anything fails, the design stays pending review. The slow part - copying the print file (up to 50 MB) to
 * disk, stamping it and storing the stamped copy - runs with no transaction held; a short transaction then locks the
 * design, checks that nothing moved meanwhile, and does the rest.</p>
 *
 * <p>The production instruction is drawn inside that transaction: it names the customer's SKU, which is issued there
 * for the snapshot the instruction is frozen in. The SKU's code is read first with its product locked until the
 * approval commits ({@link CustomerSkus#nextSku}), the one-page sheet drawn and stored - a rollback deletes it - and
 * the code checked once the SKU is issued.</p>
 */
@Service
@Transactional(readOnly = true)
public class DesignReviewService {

    /** What the Sales Manager approves: the files the customer submitted (plan D2). */
    private static final List<DesignArtifactRole> SUBMITTED = List.of(DesignArtifactRole.CUSTOMER_PREVIEW,
            DesignArtifactRole.PRINT_READY, DesignArtifactRole.TECHNICAL_SPEC);
    private static final int REASON_MAX = 2000;

    private final DesignDraftJpaRepository drafts;
    private final DesignDocumentJpaRepository documents;
    private final DesignAssetJpaRepository assets;
    private final DesignArtifactJpaRepository artifacts;
    private final DesignSnapshotJpaRepository snapshots;
    private final DesignDecisionJpaRepository decisions;
    private final PrintConfigs printConfigs;
    private final CustomerSkus customerSkus;
    private final CustomerService customers;
    private final ProductService products;
    private final UomConversionService units;
    private final FileStorage storage;
    private final FileTransfers files;
    private final PrintReadyStamp stamp;
    private final ProductionInstructionSheet instructions;
    private final ApplicationEventPublisher events;
    private final DraftViews views;
    private final DesignQuoteRequestService quoteRequests;
    private final DesignReviewRounds reviewRounds;
    private final EntityManager entityManager;
    private final TransactionOperations tx;
    private final Clock clock;

    @SuppressWarnings("java:S107")
    public DesignReviewService(DesignDraftJpaRepository drafts, DesignDocumentJpaRepository documents,
                               DesignAssetJpaRepository assets, DesignArtifactJpaRepository artifacts,
                               DesignSnapshotJpaRepository snapshots, DesignDecisionJpaRepository decisions,
                               PrintConfigs printConfigs, CustomerSkus customerSkus, CustomerService customers,
                               ProductService products, UomConversionService units, FileStorage storage,
                               FileTransfers files, PrintReadyStamp stamp, ProductionInstructionSheet instructions,
                               ApplicationEventPublisher events, DraftViews views,
                               DesignQuoteRequestService quoteRequests, DesignReviewRounds reviewRounds,
                               EntityManager entityManager, TransactionOperations tx, Clock clock) {
        this.drafts = drafts;
        this.documents = documents;
        this.assets = assets;
        this.artifacts = artifacts;
        this.snapshots = snapshots;
        this.decisions = decisions;
        this.printConfigs = printConfigs;
        this.customerSkus = customerSkus;
        this.customers = customers;
        this.products = products;
        this.units = units;
        this.storage = storage;
        this.files = files;
        this.stamp = stamp;
        this.instructions = instructions;
        this.events = events;
        this.views = views;
        this.quoteRequests = quoteRequests;
        this.reviewRounds = reviewRounds;
        this.entityManager = entityManager;
        this.tx = tx;
        this.clock = clock;
    }

    /** Customers' designs waiting for review, the longest waiting first. */
    public Page<DesignWorkflowService.DraftView> queue(Pageable page) {
        Page<DesignDraftJpaEntity> found = drafts.reviewQueue(Pages.of(page.getPageNumber(), page.getPageSize()));
        return new PageImpl<>(views.of(found.getContent()), found.getPageable(), found.getTotalElements());
    }

    /**
     * Decides a design waiting for review, at {@code version}: approved, or returned with a reason of at most
     * {@value #REASON_MAX} characters (BR-06). The decision is about exactly the version reviewed (BR-11).
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#id")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DesignWorkflowService.DraftView decide(UUID id, UUID actor, long version, boolean approved, String notes) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!approved) {
            String reason = notes == null ? "" : notes.strip();
            if (reason.isEmpty() || reason.length() > REASON_MAX) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "A design is returned with a reason of 1 to " + REASON_MAX + " characters");
            }
            return tx.execute(status -> returnDesign(id, actor, version, reason));
        }
        String note = notes == null || notes.isBlank() ? null : notes.strip();
        if (note != null && note.length() > REASON_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A note on an approval is at most " + REASON_MAX + " characters");
        }
        Approval approval = tx.execute(status -> approval(id, actor, version));
        try (PrintFileCopy submitted = PrintFileCopy.download(storage, approval.printReady().key(),
                approval.printReadyChecksum());
             PrintFileCopy stamped = PrintFileCopy.empty()) {
            // Titled by its template and design: the customer's SKU exists only once the approval commits.
            stamp.stamp(submitted, approval.template(), "Print file " + approval.template().code() + ", design " + id,
                    stamped);
            long size = stamped.size();
            if (size > FileCategory.DESIGN_RENDER.maxBytes()) {
                throw new BusinessException(ErrorCode.DESIGN_PRINT_FILE_INVALID, "Stamped, the print file is larger than "
                        + FileCategory.DESIGN_RENDER.maxBytes() / (1024 * 1024) + " MB");
            }
            try (InputStream content = Files.newInputStream(stamped.path());
                 PreparedFiles print = files.prepare(FileCategory.DESIGN_RENDER,
                         new FileUpload("print-ready.pdf", "application/pdf", size, content))) {
                return tx.execute(status -> approve(id, actor, note, approval, print));
            }
        } catch (IOException ex) {
            throw new StorageException("Could not read the stamped print file", ex);
        }
    }

    // ---- returning ----

    private DesignWorkflowService.DraftView returnDesign(UUID id, UUID actor, long version, String reason) {
        DesignDraftJpaEntity draft = lockPending(id, version);
        draft.returnToDraft(actor, reason, clock.instant());
        drafts.saveAndFlush(draft);
        decide(draft, actor, DesignDecisionType.RETURNED, reason);
        reviewRounds.decided(id, "RETURNED", actor, draft.getReviewedAt());
        events.publishEvent(new DesignReviewed(id, draft.getOwnerUserId(), "RETURNED", draft.getSku(),
                draft.getReviewedAt(), quoteRequests.openOf(id)));
        return views.of(draft);
    }

    // ---- approving ----

    /** What the approval reads first, and checks again under the lock: nothing it was decided on may have moved. */
    private record Approval(long version, String layoutChecksum, Map<DesignArtifactRole, UUID> files,
                            StoredFile printReady, String printReadyChecksum, PrintConfig template,
                            String productName, UomConversion carton) {
    }

    private Approval approval(UUID id, UUID actor, long version) {
        DesignDraftJpaEntity draft = drafts.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        requirePending(draft, version);
        DesignDocumentJpaEntity layout = documents.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "The design has no layout"));
        Map<DesignArtifactRole, UUID> current = draft.getCurrentArtifacts();
        Map<DesignArtifactRole, UUID> submitted = new java.util.EnumMap<>(DesignArtifactRole.class);
        for (DesignArtifactRole role : SUBMITTED) {
            DesignArtifactJpaEntity file = current.containsKey(role)
                    ? artifacts.findById(current.get(role)).orElse(null) : null;
            submitted.put(role, file == null ? null : file.getId());
        }
        PrintConfig template = printConfigs.byId(draft.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRINT_CONFIG_NOT_FOUND));
        for (DesignArtifactRole role : SUBMITTED) {
            DesignArtifactJpaEntity file = submitted.get(role) == null ? null
                    : artifacts.findById(submitted.get(role)).orElse(null);
            if (file == null || !file.getDraftId().equals(id) || !layout.getChecksum().equals(file.getDocumentChecksum())
                    || !Long.valueOf(template.version()).equals(file.getTemplateVersion())) {
                // The template was edited after the design was submitted: its files no longer fit it. Return it.
                throw new BusinessException(ErrorCode.DESIGN_FILES_OUTDATED, "The submitted " + role
                        + " file no longer matches the layout or the template", List.of(),
                        Map.of("roles", List.of(role.name())));
            }
        }
        DesignArtifactJpaEntity print = artifacts.findById(submitted.get(DesignArtifactRole.PRINT_READY)).orElseThrow();
        if (!"application/pdf".equals(print.storedFile().contentType())) {
            throw new BusinessException(ErrorCode.DESIGN_PRINT_FILE_INVALID, "The print file must be a PDF");
        }
        String productName = products.findById(draft.getProductId()).map(ProductSummary::name).orElse(null);
        UomConversion carton = units.conversionsOf(List.of(new Sku(draft.getSku()))).getOrDefault(draft.getSku(),
                List.of()).stream().filter(unit -> unit.active() && !unit.base() && unit.factor() > 1)
                .max(Comparator.comparingInt(UomConversion::factor)).orElse(null);
        return new Approval(draft.getVersion(), layout.getChecksum(), submitted, print.storedFile(),
                print.getChecksum(), template, productName, carton);
    }

    private DesignWorkflowService.DraftView approve(UUID id, UUID actor, String note, Approval approval,
                                                    PreparedFiles print) {
        DesignDraftJpaEntity draft = lockPending(id, approval.version());
        DesignDocumentJpaEntity layout = documents.findById(id).orElseThrow();
        if (!layout.getChecksum().equals(approval.layoutChecksum()) || !draft.getCurrentArtifacts().entrySet()
                .containsAll(approval.files().entrySet())) {
            throw new BusinessException(ErrorCode.CONFLICT, "The design changed while it was being approved");
        }
        Instant now = clock.instant();
        // The stamped print file replaces the submitted one; the instruction is new. Both name the approved layout.
        if (printConfigs.byId(draft.getTemplateId()).map(PrintConfig::version).orElse(-1L)
                != approval.template().version()) {
            throw new BusinessException(ErrorCode.CONFLICT, "The template changed while the design was being approved");
        }
        DesignArtifactJpaEntity stampedFile = newArtifact(id, DesignArtifactRole.PRINT_READY, print,
                approval.layoutChecksum(), approval.template().version());
        // The code of the SKU issued below, its product locked from here until this commits.
        String customerCode = customers.findById(draft.getCustomerId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CUSTOMER_NOT_FOUND)).code();
        String customerSku = customerSkus.nextSku(draft.getSku(), draft.getCustomerId(), customerCode);
        byte[] sheet = instructions.render(new ProductionInstructionSheet.Facts(customerSku, approval.productName(),
                draft.getSku(), approval.template(), approval.carton(), approval.layoutChecksum(),
                stampedFile.getChecksum(), actor, now));
        DesignArtifactJpaEntity sheetFile = drawnArtifact(id, DesignArtifactRole.PRODUCTION_INSTRUCTION, sheet,
                "production-instruction.pdf", approval.layoutChecksum(), approval.template().version());
        draft.attachArtifact(DesignArtifactRole.PRINT_READY, stampedFile.getId());
        draft.attachArtifact(DesignArtifactRole.PRODUCTION_INSTRUCTION, sheetFile.getId());

        List<DesignArtifactJpaEntity> bundle = new ArrayList<>();
        bundle.add(artifacts.findById(approval.files().get(DesignArtifactRole.CUSTOMER_PREVIEW)).orElseThrow());
        bundle.add(stampedFile);
        bundle.add(artifacts.findById(approval.files().get(DesignArtifactRole.TECHNICAL_SPEC)).orElseThrow());
        bundle.add(sheetFile);
        List<DesignSnapshotAsset> placed = placedImages(id, layout.getDocument());
        UUID snapshotId = Identifiers.newId();
        DesignSnapshotJpaEntity snapshot = snapshots.saveAndFlush(DesignSnapshotJpaEntity.approved(snapshotId, draft,
                bundle, layout.getDocument(), layout.getChecksum(), placed, TemplateShapes.json(approval.template()),
                actor, now));

        CustomerSku issued = customerSkus.issue(new IssueCustomerSkuCommand(snapshotId, draft.getCustomerId(),
                customerCode, draft.getSku(), draft.getName()));
        if (!issued.sku().equals(customerSku)) {
            // Its product is locked since the code was read: only something issuing around that lock gets here.
            throw new BusinessException(ErrorCode.CONFLICT,
                    "The customer's SKU changed while the design was being approved; approve it again");
        }

        // From here what was approved is what is printed: no file or image of it can be overwritten or deleted for the
        // confirmed-design retention period once this commits (S3 Object Lock).
        bundle.forEach(file -> files.retainAfterCommit(file.storedFile().key(), RecordRetention.CONFIRMED_DESIGN));
        placed.forEach(asset -> files.retainAfterCommit(asset.storageKey(), RecordRetention.CONFIRMED_DESIGN));
        print.adopt();

        draft.approve(actor, now);
        drafts.saveAndFlush(draft);
        decide(draft, actor, DesignDecisionType.APPROVED, "Snapshot " + snapshotId + ", SKU " + issued.sku()
                + ", checksum " + snapshot.getChecksum() + (note == null ? "" : ". " + note));
        reviewRounds.decided(id, "APPROVED", actor, now);
        events.publishEvent(new DesignReviewed(id, draft.getOwnerUserId(), "APPROVED", issued.sku(), now,
                quoteRequests.openOf(id)));
        return views.of(draft);
    }

    private DesignArtifactJpaEntity newArtifact(UUID designId, DesignArtifactRole role, PreparedFiles prepared,
                                                String layoutChecksum, long templateVersion) {
        PreparedFiles.PreparedFile file = prepared.single();
        var row = new DesignArtifactJpaEntity(Identifiers.newId(), designId, role, file.file(), file.sha256());
        row.renderedFrom(layoutChecksum, templateVersion);
        return artifacts.saveAndFlush(row);
    }

    /** A PDF drawn in the approval's transaction, stored so that a rollback deletes it again. */
    private DesignArtifactJpaEntity drawnArtifact(UUID designId, DesignArtifactRole role, byte[] pdf, String name,
                                                  String layoutChecksum, long templateVersion) {
        String checksum = Checksums.sha256Hex(pdf);
        StoredFile file = files.store(FileCategory.DESIGN_RENDER, new FileUpload(name, "application/pdf", pdf.length,
                new ByteArrayInputStream(pdf), checksum));
        var row = new DesignArtifactJpaEntity(Identifiers.newId(), designId, role, file, checksum);
        row.renderedFrom(layoutChecksum, templateVersion);
        return artifacts.saveAndFlush(row);
    }

    /** The images the approved layout places, as the snapshot keeps them. */
    private List<DesignSnapshotAsset> placedImages(UUID designId, String layout) {
        Set<String> named = new HashSet<>();
        if (CanonicalJson.parse(layout) instanceof Map<?, ?> root && root.get("surfaces") instanceof Map<?, ?> surfaces) {
            for (Object surface : surfaces.values()) {
                if (surface instanceof Map<?, ?> content && content.get("elements") instanceof List<?> elements) {
                    for (Object element : elements) {
                        if (element instanceof Map<?, ?> map && "image".equals(map.get("type"))
                                && map.get("assetId") instanceof String assetId) {
                            named.add(assetId);
                        }
                    }
                }
            }
        }
        return assets.findByDraftIdOrderByCreatedAtDescIdDesc(designId).stream()
                .filter(asset -> named.contains(asset.getAssetId().toString()))
                .map(asset -> new DesignSnapshotAsset(asset.getAssetId(), asset.getStorageKey(), asset.getOriginalName(),
                        asset.getContentType(), asset.getSizeBytes(), asset.getChecksum(), asset.getWidthPx(),
                        asset.getHeightPx()))
                .sorted(Comparator.comparing(asset -> asset.assetId().toString()))
                .toList();
    }

    // ---- shared ----

    private DesignDraftJpaEntity lockPending(UUID id, long version) {
        DesignDraftJpaEntity draft = drafts.lockById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // The lock read may hand back a copy loaded before: read the row as it is now.
        entityManager.refresh(draft);
        requirePending(draft, version);
        return draft;
    }

    /** A customer's design waiting for review, at the version the reviewer saw (BR-11). */
    private static void requirePending(DesignDraftJpaEntity draft, long version) {
        if (draft.getTemplateId() == null || draft.getStatus() == DesignStatus.ARCHIVED) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        DesignWorkflow.requireVersion(draft.getVersion(), version);
        if (draft.getStatus() != DesignStatus.PENDING_REVIEW) {
            throw new BusinessException(ErrorCode.CONFLICT, "Only a design waiting for review is decided");
        }
    }

    private void decide(DesignDraftJpaEntity draft, UUID actor, DesignDecisionType type, String notes) {
        decisions.save(new DesignDecisionJpaEntity(draft, actor, type.name(), notes));
    }
}
