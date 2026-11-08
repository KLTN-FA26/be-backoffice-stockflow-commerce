package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.domain.Sku;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.json.CanonicalJson;
import com.stockflow.contracts.DesignSubmittedForReview;
import com.stockflow.design.api.DesignArtifactRole;
import com.stockflow.design.internal.domain.CustomerDesignRules;
import com.stockflow.design.internal.domain.DesignDecisionType;
import com.stockflow.design.internal.domain.DesignWorkflow;
import com.stockflow.design.internal.domain.LayoutPreflight;
import com.stockflow.design.internal.entity.DesignArtifactJpaEntity;
import com.stockflow.design.internal.entity.DesignDecisionJpaEntity;
import com.stockflow.design.internal.entity.DesignDocumentJpaEntity;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.repository.DesignArtifactJpaRepository;
import com.stockflow.design.internal.repository.DesignAssetJpaRepository;
import com.stockflow.design.internal.repository.DesignDecisionJpaRepository;
import com.stockflow.design.internal.repository.DesignDocumentJpaRepository;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.inventory.api.UomConversionService;
import com.stockflow.product.api.PrintConfig;
import com.stockflow.product.api.PrintConfigs;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * A customer submits their design for review (kltn-docs 12 §4.3): the server's preflight runs on the layout as it is
 * saved (BR-05; {@link LayoutPreflight}), and the three files the studio rendered from it must name that layout's
 * checksum (BR-10; plan D2). Passing, the design is locked (BR-07), waits for the Sales Manager, and they are told
 * through {@link DesignSubmittedForReview}. Failing, it stays a draft and the failure is written to its history
 * ({@code PREFLIGHT_FAILED}), without moving its version.
 *
 * <h2>Three steps</h2>
 * <ol>
 *   <li>A short read checks the design, runs the layout preflight - arithmetic on a layout of at most a megabyte - and
 *       finds the files submitted with it: each must name the layout's checksum and the template's version, and the
 *       print file and the technical spec must be PDFs.</li>
 *   <li>With no transaction held, the print file - up to 50 MB - is read, checked against its checksum, and checked to
 *       be the studio's for this template ({@link PrintFileGeometry}): a file the press cannot use never reaches the
 *       Sales Manager. A failure is written to the history in a transaction of its own, committed before the 422.</li>
 *   <li>A short transaction locks the design and submits it - only if its version is still the one read in step 1:
 *       every change to the layout or the files moves it.</li>
 * </ol>
 */
@Service
@Transactional
public class DesignSubmissionService {

    /** What the studio renders when the customer submits (plan D2); the Sales Manager approves exactly these. */
    static final Set<DesignArtifactRole> SUBMITTED_FILES = EnumSet.of(DesignArtifactRole.CUSTOMER_PREVIEW,
            DesignArtifactRole.PRINT_READY, DesignArtifactRole.TECHNICAL_SPEC);

    private static final int NOTES_MAX = 4000;

    private final DesignDraftJpaRepository drafts;
    private final DesignDocumentJpaRepository documents;
    private final DesignAssetJpaRepository assets;
    private final DesignArtifactJpaRepository artifacts;
    private final DesignDecisionJpaRepository decisions;
    private final PrintConfigs printConfigs;
    private final UomConversionService units;
    private final ApplicationEventPublisher events;
    private final DraftViews views;
    private final EntityManager entityManager;
    private final TransactionOperations tx;
    private final Clock clock;
    private final com.stockflow.common.storage.FileStorage storage;

    public DesignSubmissionService(DesignDraftJpaRepository drafts, DesignDocumentJpaRepository documents,
                                   DesignAssetJpaRepository assets, DesignArtifactJpaRepository artifacts,
                                   DesignDecisionJpaRepository decisions, PrintConfigs printConfigs,
                                   UomConversionService units, ApplicationEventPublisher events, DraftViews views,
                                   EntityManager entityManager, TransactionOperations tx, Clock clock,
                                   com.stockflow.common.storage.FileStorage storage) {
        this.storage = storage;
        this.drafts = drafts;
        this.documents = documents;
        this.assets = assets;
        this.artifacts = artifacts;
        this.decisions = decisions;
        this.printConfigs = printConfigs;
        this.units = units;
        this.events = events;
        this.views = views;
        this.entityManager = entityManager;
        this.tx = tx;
        this.clock = clock;
    }

    /** What the first read found: why the design cannot go, or the print file to check before it does. */
    private record Plan(List<LayoutPreflight.Issue> issues, List<String> outdatedFiles, PrintConfig template,
                        String printFileKey, String printFileChecksum) {
    }

    /**
     * Submits the design at {@code version}.
     *
     * @throws BusinessException 422 {@code DESIGN_PREFLIGHT_FAILED} with {@code details.issues} - each
     *         {@code {rule, surfaceCode?, elementId?, message, values?}} - when the layout fails preflight or the print
     *         file is not the template's ({@code PRINT_FILE_INVALID}); 409
     *         {@code DESIGN_FILES_OUTDATED} with {@code details.roles} when a submitted file is missing or was rendered
     *         from another layout; 409 {@code DESIGN_NOT_DRAFT}; 409 on a stale version; 404 for anyone but the owner
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design", resourceId = "#id")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DesignWorkflowService.DraftView submit(UUID id, UUID actor, long version) {
        Plan plan = tx.execute(status -> plan(id, actor, version));
        List<LayoutPreflight.Issue> issues = new ArrayList<>(plan.issues());
        if (issues.isEmpty() && plan.outdatedFiles().isEmpty()) {
            String problem = PrintFileGeometry.problem(printFile(plan), plan.template());
            if (problem != null) {
                issues.add(new LayoutPreflight.Issue("PRINT_FILE_INVALID", null, null, problem));
            }
        }
        if (!issues.isEmpty()) {
            tx.executeWithoutResult(status -> recordFailure(id, actor, issues));
            throw new BusinessException(ErrorCode.DESIGN_PREFLIGHT_FAILED,
                    "The design did not pass preflight: " + issues.size() + " problem(s)", List.of(),
                    Map.of("issues", LayoutPreflight.asMaps(issues)));
        }
        if (!plan.outdatedFiles().isEmpty()) {
            throw new BusinessException(ErrorCode.DESIGN_FILES_OUTDATED,
                    "Render the files again from the layout as it is now: " + plan.outdatedFiles(), List.of(),
                    Map.of("roles", plan.outdatedFiles()));
        }
        return tx.execute(status -> submitLocked(id, actor, version, plan));
    }

    private Plan plan(UUID id, UUID actor, long version) {
        DesignDraftJpaEntity draft = drafts.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        CustomerDesignRules.requireOwner(actor, draft.getOwnerUserId(), draft.getStatus());
        DesignWorkflow.requireVersion(draft.getVersion(), version);
        CustomerDesignRules.requireDraft(draft.getStatus());
        if (draft.getTemplateId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "This design was not started on a template");
        }
        PrintConfig template = printConfigs.byId(draft.getTemplateId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRINT_CONFIG_NOT_FOUND));
        DesignDocumentJpaEntity layout = documents.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "This design has no layout"));

        List<LayoutPreflight.Issue> issues = new ArrayList<>();
        if (!printConfigs.designable(draft.getTemplateId())) {
            issues.add(new LayoutPreflight.Issue("VARIANT_UNAVAILABLE", null, null,
                    "The variant or its product can no longer be designed on"));
        }
        if (!hasCartonSpec(draft.getSku())) {
            // B25: what a customer orders in; without it nothing printed could be quoted or packed.
            issues.add(new LayoutPreflight.Issue("CARTON_SPEC_MISSING", null, null,
                    "The variant has no carton specification yet; ask the sales staff"));
        }
        issues.addAll(LayoutPreflight.check(CanonicalJson.parse(layout.getDocument()), template, assetsOf(id)));
        if (!issues.isEmpty()) {
            return new Plan(issues, List.of(), template, null, null);
        }
        Map<DesignArtifactRole, DesignArtifactJpaEntity> files = currentFiles(draft);
        List<String> outdated = new ArrayList<>();
        for (DesignArtifactRole role : SUBMITTED_FILES) {
            DesignArtifactJpaEntity file = files.get(role);
            if (file == null || !layout.getChecksum().equals(file.getDocumentChecksum())
                    || !Long.valueOf(template.version()).equals(file.getTemplateVersion())) {
                outdated.add(role.name());
            }
        }
        outdated.sort(String::compareTo);
        if (!outdated.isEmpty()) {
            return new Plan(List.of(), outdated, template, null, null);
        }
        // The press reads the print file, the producer the technical spec: both are documents, never a picture.
        for (DesignArtifactRole role : List.of(DesignArtifactRole.PRINT_READY, DesignArtifactRole.TECHNICAL_SPEC)) {
            if (!"application/pdf".equals(files.get(role).storedFile().contentType())) {
                issues.add(new LayoutPreflight.Issue("PRINT_FILE_INVALID", null, null,
                        "The " + role + " file must be a PDF"));
            }
        }
        DesignArtifactJpaEntity print = files.get(DesignArtifactRole.PRINT_READY);
        return new Plan(issues, List.of(), template, print.storedFile().key(), print.getChecksum());
    }

    /** The print file's bytes, which must still be the bytes uploaded. */
    private byte[] printFile(Plan plan) {
        byte[] bytes;
        try (java.io.InputStream in = storage.download(plan.printFileKey())) {
            bytes = in.readAllBytes();
        } catch (java.io.IOException ex) {
            throw new com.stockflow.common.storage.StorageException("Could not read the print file", ex);
        }
        if (!com.stockflow.common.storage.Checksums.sha256Hex(bytes).equals(plan.printFileChecksum())) {
            throw new BusinessException(ErrorCode.CONFLICT, "The stored print file differs from the one uploaded");
        }
        return bytes;
    }

    /** {@code PREFLIGHT_FAILED} in the history, without touching the design: its version does not move. */
    private void recordFailure(UUID id, UUID actor, List<LayoutPreflight.Issue> issues) {
        DesignDraftJpaEntity draft = drafts.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        decide(draft, actor, DesignDecisionType.PREFLIGHT_FAILED, summary(issues));
    }

    /** Under the design's lock: submitted, if nothing moved since it was checked. */
    private DesignWorkflowService.DraftView submitLocked(UUID id, UUID actor, long version, Plan plan) {
        DesignDraftJpaEntity draft = drafts.lockById(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // The lock read may hand back a copy loaded before: read the row as it is now.
        entityManager.refresh(draft);
        CustomerDesignRules.requireOwner(actor, draft.getOwnerUserId(), draft.getStatus());
        DesignWorkflow.requireVersion(draft.getVersion(), version);
        CustomerDesignRules.requireDraft(draft.getStatus());
        long templateNow = printConfigs.byId(draft.getTemplateId()).map(PrintConfig::version).orElse(-1L);
        if (templateNow != plan.template().version()) {
            throw new BusinessException(ErrorCode.DESIGN_FILES_OUTDATED,
                    "The template changed while the design was being submitted", List.of(),
                    Map.of("roles", SUBMITTED_FILES.stream().map(Enum::name).sorted().toList()));
        }
        Instant now = clock.instant();
        draft.submitForReview(now);
        drafts.saveAndFlush(draft);
        decide(draft, actor, DesignDecisionType.SUBMITTED_FOR_REVIEW, "Layout " + documents.findById(id)
                .map(DesignDocumentJpaEntity::getChecksum).orElse("-"));
        events.publishEvent(new DesignSubmittedForReview(id, draft.getCustomerId(), draft.getSku(), now));
        return views.of(draft);
    }

    /** The design's images as the preflight reads them: stored type and size, measured pixels. */
    private Map<String, LayoutPreflight.Asset> assetsOf(UUID designId) {
        Map<String, LayoutPreflight.Asset> found = new HashMap<>();
        assets.findByDraftIdOrderByCreatedAtDescIdDesc(designId).forEach(asset -> found.put(
                asset.getAssetId().toString(), new LayoutPreflight.Asset(asset.getContentType(), asset.getSizeBytes(),
                        asset.getWidthPx(), asset.getHeightPx())));
        return found;
    }

    /** An active packing unit of more than one base unit: the variant's carton specification (kltn-docs 01 BR-03). */
    private boolean hasCartonSpec(String sku) {
        return units.conversionsOf(List.of(new Sku(sku))).getOrDefault(sku, List.of()).stream()
                .anyMatch(unit -> unit.active() && !unit.base() && unit.factor() > 1);
    }

    /** The design's current file of each role, its own. */
    private Map<DesignArtifactRole, DesignArtifactJpaEntity> currentFiles(DesignDraftJpaEntity draft) {
        Map<DesignArtifactRole, UUID> current = draft.getCurrentArtifacts();
        Map<UUID, DesignArtifactJpaEntity> stored = artifacts.findAllById(current.values()).stream()
                .filter(file -> file.getDraftId().equals(draft.getId()))
                .collect(Collectors.toMap(DesignArtifactJpaEntity::getId, file -> file));
        Map<DesignArtifactRole, DesignArtifactJpaEntity> files = new java.util.EnumMap<>(DesignArtifactRole.class);
        current.forEach((role, artifactId) -> {
            if (stored.containsKey(artifactId)) {
                files.put(role, stored.get(artifactId));
            }
        });
        return files;
    }

    private void decide(DesignDraftJpaEntity draft, UUID actor, DesignDecisionType type, String notes) {
        decisions.save(new DesignDecisionJpaEntity(draft, actor, type.name(), notes));
    }

    /** The findings as one line each, cut to what the history holds. */
    private static String summary(List<LayoutPreflight.Issue> issues) {
        String text = issues.stream().map(issue -> issue.rule()
                        + (issue.surfaceCode() == null ? "" : " " + issue.surfaceCode())
                        + (issue.elementId() == null ? "" : "/" + issue.elementId()))
                .collect(Collectors.joining("; "));
        return text.length() <= NOTES_MAX ? text : text.substring(0, NOTES_MAX - 3) + "...";
    }
}
