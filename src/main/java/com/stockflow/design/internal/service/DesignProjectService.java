package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.customer.api.CustomerSummary;
import com.stockflow.design.internal.domain.CustomerDesignRules;
import com.stockflow.design.internal.domain.DesignDecisionType;
import com.stockflow.design.internal.domain.DesignProjectRules;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.domain.DesignWorkflow;
import com.stockflow.design.internal.domain.ProjectEventType;
import com.stockflow.design.internal.domain.ProjectStatus;
import com.stockflow.design.internal.domain.QuoteRequestStatus;
import com.stockflow.design.internal.entity.DesignDecisionJpaEntity;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.entity.DesignProjectEventJpaEntity;
import com.stockflow.design.internal.entity.DesignProjectJpaEntity;
import com.stockflow.design.internal.repository.DesignDecisionJpaRepository;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectAssetJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectEventJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectQuoteRequestLineJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectQuoteRequestJpaRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's design projects (kltn-docs 12 §4.9, §5.4; BR-17, BR-22; B42): created, renamed and closed by their owner,
 * holding designs of that customer - at most {@value DesignProjectRules#MAX_DESIGNS}, archived ones not counted. A
 * project is optional (BR-17): a design started without one stands alone, and so do the revisions and clones of a
 * design that stands alone. A design started in a project, or a revision or a clone the caller puts in one or that
 * follows its source into one, is in it from its first version (BR-23.3). An existing design joins when its owner
 * adds it, from no project or moved from another open project of theirs. It never leaves for no project: a design is taken out of one only by
 * moving it into another (BR-23.4). A design waiting for review, or named by an open quote request of its
 * project, stays where it is. Joining or moving changes nothing else about the design.
 *
 * <p>Only the owner sees a project (BR-17); the coordinator and the sales staff see its name alone, on a review round
 * and on a project quote request. Every write takes the project's row lock first, then a design's: the order a batch
 * locks them in.</p>
 */
@Service
@Transactional
public class DesignProjectService {

    private static final Set<ProjectStatus> LISTED = EnumSet.allOf(ProjectStatus.class);

    private final DesignProjectJpaRepository projects;
    private final DesignDraftJpaRepository drafts;
    private final DesignProjectAssetJpaRepository assets;
    private final DesignProjectEventJpaRepository history;
    private final DesignDecisionJpaRepository decisions;
    private final DesignProjectQuoteRequestLineJpaRepository quoteRequestLines;
    private final DesignProjectQuoteRequestJpaRepository quoteRequests;
    private final CustomerService customers;
    private final DraftViews views;
    private final EntityManager entityManager;
    private final Clock clock;

    public DesignProjectService(DesignProjectJpaRepository projects, DesignDraftJpaRepository drafts,
                                DesignProjectAssetJpaRepository assets, DesignProjectEventJpaRepository history,
                                DesignDecisionJpaRepository decisions,
                                DesignProjectQuoteRequestLineJpaRepository quoteRequestLines,
                                DesignProjectQuoteRequestJpaRepository quoteRequests, CustomerService customers,
                                DraftViews views, EntityManager entityManager, Clock clock) {
        this.projects = projects;
        this.drafts = drafts;
        this.assets = assets;
        this.history = history;
        this.decisions = decisions;
        this.quoteRequestLines = quoteRequestLines;
        this.quoteRequests = quoteRequests;
        this.customers = customers;
        this.views = views;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** A project in a list: what it is and how many designs it holds. */
    public record ProjectSummary(UUID id, String name, String description, ProjectStatus status, long version,
                                 long designCount, Instant createdAt, Instant archivedAt) {
    }

    /** A project opened: its designs side by side (§4.9 step 30), archived ones aside, and its library's size. */
    public record ProjectView(UUID id, String name, String description, ProjectStatus status, long version,
                              Instant createdAt, Instant archivedAt, List<DesignWorkflowService.DraftView> designs,
                              long assetCount) {
    }

    /** One step of a project's history (§5.4). */
    public record EventView(UUID actorId, String event, String notes, Instant createdAt) {
    }

    /** The customer signed in starts a project (§4.9 step 27). Only an active customer does (BR-01). */
    @Auditable(action = AuditAction.CREATE, resourceType = "design-project")
    public ProjectView create(UUID actor, String name, String description) {
        CustomerSummary customer = activeCustomerOf(actor);
        DesignProjectJpaEntity project = projects.saveAndFlush(new DesignProjectJpaEntity(customer.customerId(), actor,
                name.strip(), blankToNull(description)));
        record(project, actor, ProjectEventType.PROJECT_CREATED, project.getName());
        return view(project);
    }

    /** The caller's own projects, the newest first: one status or both. */
    @Transactional(readOnly = true)
    public Page<ProjectSummary> list(UUID actor, String status, Pageable page) {
        authenticated(actor);
        Set<ProjectStatus> statuses = status == null || status.isBlank() ? LISTED : Set.of(parseStatus(status));
        Page<DesignProjectJpaEntity> found = projects.findOwnedBy(actor, statuses, Pages.of(page.getPageNumber(),
                page.getPageSize()));
        java.util.Map<UUID, Long> counts = found.isEmpty() ? java.util.Map.of()
                : drafts.countInProjects(found.getContent().stream().map(DesignProjectJpaEntity::getId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(DesignDraftJpaRepository.ProjectCount::getProjectId,
                        DesignDraftJpaRepository.ProjectCount::getDesigns));
        return found.map(p -> new ProjectSummary(p.getId(), p.getName(), p.getDescription(), p.getStatus(),
                p.getVersion(), counts.getOrDefault(p.getId(), 0L), p.getCreatedAt(), p.getArchivedAt()));
    }

    @Transactional(readOnly = true)
    public ProjectView get(UUID id, UUID actor) {
        return view(own(id, actor));
    }

    /** Renamed or described anew while open (§4.9; BR-17). */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project", resourceId = "#id")
    public ProjectView describe(UUID id, UUID actor, long version, String name, String description) {
        DesignProjectJpaEntity project = lockOwn(id, actor, version);
        DesignProjectRules.requireActive(project.getStatus());
        project.describe(name.strip(), blankToNull(description));
        return view(projects.saveAndFlush(project));
    }

    /**
     * Closed for good (BR-22, B42), once none of its designs waits for review. The designs are locked first, so a
     * submission that committed before is seen; one submitted after is the design's own, used on its own.
     *
     * <p>An open quote request of the project (BR-25) stays open and goes on to its quote - so only once every design
     * on it is approved: a line returned for changes could no longer be fixed or dropped in a closed project. Locked
     * after the designs, as every writer of the request locks it (project, designs, request).</p>
     *
     * @throws BusinessException 409 {@code DESIGN_PROJECT_HAS_PENDING}; 409
     *         {@code PROJECT_HAS_UNAPPROVED_QUOTE_REQUEST} with {@code details.requestId} and {@code details.designIds}
     *         for an open request naming a design not approved
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project", resourceId = "#id")
    public ProjectView archive(UUID id, UUID actor, long version) {
        DesignProjectJpaEntity project = lockOwn(id, actor, version);
        DesignProjectRules.requireActive(project.getStatus());
        if (drafts.lockInProject(id).stream().anyMatch(d -> {
            entityManager.refresh(d);
            return d.getStatus() == DesignStatus.PENDING_REVIEW;
        })) {
            throw new BusinessException(ErrorCode.DESIGN_PROJECT_HAS_PENDING,
                    "A design of this project waits for review; close the project once it is decided");
        }
        requireOpenRequestApproved(id);
        project.archive(clock.instant());
        projects.saveAndFlush(project);
        record(project, actor, ProjectEventType.PROJECT_ARCHIVED, project.getName());
        return view(project);
    }

    /** The project's open quote request, locked: every design on it approved, or the project does not close. */
    private void requireOpenRequestApproved(UUID projectId) {
        quoteRequests.findOpen(projectId).flatMap(found -> quoteRequests.lockById(found.getId())).ifPresent(request -> {
            entityManager.refresh(request);
            if (request.getStatus() != QuoteRequestStatus.OPEN) {
                return;
            }
            List<String> waiting = quoteRequests.unapprovedDesigns(request.getId()).stream().map(UUID::toString)
                    .toList();
            if (!waiting.isEmpty()) {
                throw new BusinessException(ErrorCode.PROJECT_HAS_UNAPPROVED_QUOTE_REQUEST, waiting.size()
                        + " design(s) of the project's open quote request are not approved; drop them or close the "
                        + "request first", List.of(), Map.of("requestId", request.getId().toString(),
                        "designIds", waiting), "refusal.design.projectHasUnapprovedQuoteRequest", waiting.size());
            }
        });
    }

    /**
     * Adds one of the owner's designs, not archived, of the project's customer (§4.9 step 28, BR-17, BR-23.4): one in
     * no project, or one moved here from another of the owner's projects. Adding a design already in it changes
     * nothing.
     *
     * <p>A move takes the design out of work its project has under way, so the source must be open (a closed project
     * keeps its designs as they were when it closed, BR-22), the design must not wait for review (its round is the
     * source's) and no open quote request of the source may name it (the sales staff answer that request over the
     * designs it names). Both projects are locked in ascending id order, so two moves in opposite directions cannot
     * deadlock, and the design after them; the source's lock also orders the move against a quote request asked over
     * that project, which takes the same lock first. The design's history records both steps, in the order they
     * happen: {@code REMOVED_FROM_PROJECT} naming the source, then {@code ADDED_TO_PROJECT} naming this project.</p>
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project", resourceId = "#id")
    public ProjectView addDesign(UUID id, UUID actor, UUID designId) {
        authenticated(actor);
        UUID from = drafts.projectIdOf(designId);
        DesignProjectJpaEntity project;
        DesignProjectJpaEntity source = null;
        if (from == null || from.equals(id)) {
            project = lock(id, actor);
        } else if (id.compareTo(from) < 0) {
            project = lock(id, actor);
            source = lock(from, actor);
        } else {
            source = lock(from, actor);
            project = lock(id, actor);
        }
        DesignProjectRules.requireActive(project.getStatus());
        DesignDraftJpaEntity draft = lockOwnDesign(designId, actor, project);
        if (!Objects.equals(from, draft.getProjectId())) {
            // Read before the locks: the design changed project since, and the project now holding it is not locked.
            throw new BusinessException(ErrorCode.CONFLICT, "The design moved to another project meanwhile; try again");
        }
        if (id.equals(from)) {
            return view(project);
        }
        DesignProjectRules.requireJoinable(draft.getStatus(), from);
        if (source != null) {
            requireMovableOutOf(source, designId);
        }
        DesignProjectRules.requireRoomForDesign(drafts.countInProject(id));
        draft.joinProject(id);
        drafts.saveAndFlush(draft);
        if (source != null) {
            decide(draft, actor, DesignDecisionType.REMOVED_FROM_PROJECT, "Project " + from);
        }
        decide(draft, actor, DesignDecisionType.ADDED_TO_PROJECT, "Project " + id);
        return view(project);
    }

    /**
     * The project a design moves out of (BR-23.4): open, and asking no quote over the design (FE request 2026-11-25,
     * point A). Each refusal names the source in {@code details.projectId}.
     */
    private void requireMovableOutOf(DesignProjectJpaEntity source, UUID designId) {
        Map<String, Object> details = Map.of("projectId", source.getId());
        if (source.getStatus() != ProjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.DESIGN_PROJECT_ARCHIVED,
                    "The design's project is closed; its designs stay in it", List.of(), details);
        }
        if (quoteRequestLines.inOpenRequest(designId)) {
            throw new BusinessException(ErrorCode.DESIGN_IN_OPEN_QUOTE_REQUEST,
                    "An open quote request of the design's project names it; it stays there until that is answered",
                    List.of(), details);
        }
    }

    /** The project's history, the newest first, to its owner. */
    @Transactional(readOnly = true)
    public Page<EventView> history(UUID id, UUID actor, Pageable page) {
        own(id, actor);
        return history.findByProjectIdOrderByCreatedAtDescIdDesc(id, Pages.of(page.getPageNumber(),
                page.getPageSize())).map(e -> new EventView(e.getActorId(), e.getEvent(), e.getNotes(),
                e.getCreatedAt()));
    }

    /**
     * The project a new design is started in (§4.1 step 3), locked in the caller's transaction before the
     * design is written: the owner's, of {@code customerId}, open and with room. Checked first so that a design is
     * never left without the project it was started in because that project refused it after it was saved; and
     * locked before the customer's draft limit is, the order every path that writes a new design into a project
     * keeps.
     */
    DesignProjectJpaEntity lockForNewDesign(UUID projectId, UUID actor, UUID customerId) {
        DesignProjectJpaEntity project = lock(projectId, actor);
        if (!project.getCustomerId().equals(customerId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        DesignProjectRules.requireActive(project.getStatus());
        DesignProjectRules.requireRoomForDesign(drafts.countInProject(projectId));
        return project;
    }

    /**
     * Writes a new design into the project {@link #lockForNewDesign} locked for it: in it from its first version, so a
     * design that belongs to a project is never, even within its own transaction, a design of no project, and its
     * version starts at 0 as any new design's does. A {@code null} project is a stand-alone design (kltn-docs 12
     * BR-17), written as it is.
     */
    void writeNew(DesignProjectJpaEntity project, DesignDraftJpaEntity draft, UUID actor) {
        if (project == null) {
            drafts.saveAndFlush(draft);
            return;
        }
        draft.joinProject(project.getId());
        drafts.saveAndFlush(draft);
        decide(draft, actor, DesignDecisionType.ADDED_TO_PROJECT, "Project " + project.getId());
    }

    /**
     * The project a revision or a clone of {@code sourceId} is written into (§4.7, FE request 2026-11-13 E-3, BR-23.3),
     * locked as {@link #lockForNewDesign} locks one - before the customer's draft limit and before anything is
     * written: {@code requested} when the caller names one, any open project of theirs with room, the source's or
     * another; otherwise the source's own while it is open and has room. A closed or full source project refuses the
     * new design, naming itself in {@code details.projectId} so the studio can offer another. Nothing is written then:
     * a revision or a clone of a design in a project never stands alone, where B42 as first built let one stand alone
     * when its source's project was closed or full.
     *
     * <p>Which project the source is in is read again under that project's lock, which moving a design takes too: a
     * source moved meanwhile is a conflict to try again, rather than a second project locked out of order. A source in
     * no project (a project is optional, kltn-docs 12 BR-17, §4.7) has none to lend: unless the caller names one, the
     * new design stands alone as its source does, and the result is null.</p>
     */
    DesignProjectJpaEntity lockForCopy(UUID sourceId, UUID requested, UUID actor, UUID customerId) {
        if (requested != null) {
            return lockForNewDesign(requested, actor, customerId);
        }
        UUID sourceProject = drafts.projectIdOf(sourceId);
        if (sourceProject == null) {
            return null;
        }
        DesignProjectJpaEntity project = projects.lockById(sourceProject)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // The lock read may hand back a copy this transaction loaded before: read the row as it is now.
        entityManager.refresh(project);
        if (!sourceProject.equals(drafts.projectIdOf(sourceId))) {
            throw new BusinessException(ErrorCode.CONFLICT, "The design moved to another project meanwhile; try again");
        }
        Map<String, Object> details = Map.of("projectId", sourceProject);
        if (project.getStatus() != ProjectStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.DESIGN_PROJECT_ARCHIVED,
                    "The design's project is closed; name an open project to put the new design in", List.of(),
                    details);
        }
        if (drafts.countInProject(sourceProject) >= DesignProjectRules.MAX_DESIGNS) {
            throw new BusinessException(ErrorCode.DESIGN_PROJECT_FULL, "The design's project holds at most "
                    + DesignProjectRules.MAX_DESIGNS + " designs; name another project to put the new design in",
                    List.of(), details);
        }
        return project;
    }

    /**
     * The check that closes {@link #lockForCopy} for a source that was in no project when it was read: it has no
     * project row to lock, so after the customer's draft limit is locked - the last lock before the copy is written -
     * the source is read once more, and one that joined a project meanwhile is a conflict to try again, not a copy
     * left standing alone beside a source that is no longer alone. Nothing to check when the copy goes into a project.
     */
    void requireStillInNoProject(UUID sourceId, DesignProjectJpaEntity project) {
        if (project == null && drafts.projectIdOf(sourceId) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "The design joined a project meanwhile; try again");
        }
    }

    /**
     * Records that the studio's own preflight refused a batch of the project (FE request 2026-11-13 E-4; kltn-docs 12
     * §5.4 {@code BATCH_PREFLIGHT_FAILED}, BR-13) - findings the backend cannot see itself, such as text crossing a
     * seam. Nothing else changes; each design records its own refusal on its own. The findings come from the browser
     * and are not trusted: only the owner of an open project records them, every design named must be in it, and the
     * notes keep the rule, surface and element of each finding - never a free-text message - in the form the
     * backend's own refusal writes ({@code DesignBatchSubmissionService}), cut to what the history holds.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project", resourceId = "#id")
    public EventView recordPreflightFailure(UUID id, UUID actor, Map<UUID, List<ReportedIssue>> issuesByDesign) {
        DesignProjectJpaEntity project = lockActive(id, actor);
        for (UUID designId : issuesByDesign.keySet()) {
            DesignDraftJpaEntity draft = drafts.findById(designId)
                    .filter(found -> id.equals(found.getProjectId()))
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            CustomerDesignRules.requireOwner(actor, draft.getOwnerUserId(), draft.getStatus());
        }
        String notes = issuesByDesign.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue().stream().map(ReportedIssue::summary).distinct()
                        .collect(Collectors.joining(", ")))
                .collect(Collectors.joining("; "));
        DesignProjectEventJpaEntity event = history.saveAndFlush(new DesignProjectEventJpaEntity(project.getId(), actor,
                ProjectEventType.BATCH_PREFLIGHT_FAILED.name(), notes));
        return new EventView(event.getActorId(), event.getEvent(), event.getNotes(), event.getCreatedAt());
    }

    /** One finding the studio reported: its rule, and where it is if it says so. */
    public record ReportedIssue(String rule, String surfaceCode, String elementId) {

        /** As {@code DesignSubmissionService} writes a finding into the history. */
        String summary() {
            return rule + (surfaceCode == null ? "" : " " + surfaceCode) + (elementId == null ? "" : "/" + elementId);
        }
    }

    /** The owner's project, locked, read as it is now, at the version they saw. */
    private DesignProjectJpaEntity lockOwn(UUID id, UUID actor, long version) {
        DesignProjectJpaEntity project = lock(id, actor);
        DesignWorkflow.requireVersion(project.getVersion(), version);
        return project;
    }

    /** The owner's open project, locked, read as it is now. */
    DesignProjectJpaEntity lockActive(UUID id, UUID actor) {
        DesignProjectJpaEntity project = lock(id, actor);
        DesignProjectRules.requireActive(project.getStatus());
        return project;
    }

    /** The owner's project, open or closed, locked, read as it is now. */
    DesignProjectJpaEntity lock(UUID id, UUID actor) {
        authenticated(actor);
        DesignProjectJpaEntity project = projects.lockById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // The lock read may hand back a copy this transaction loaded before: read the row as it is now.
        entityManager.refresh(project);
        DesignProjectRules.requireOwner(actor, project.getOwnerUserId());
        return project;
    }

    /** The owner's own project, unlocked. */
    DesignProjectJpaEntity own(UUID id, UUID actor) {
        authenticated(actor);
        DesignProjectJpaEntity project = projects.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        DesignProjectRules.requireOwner(actor, project.getOwnerUserId());
        return project;
    }

    /** One of the owner's designs, of the project's customer, locked after the project. */
    private DesignDraftJpaEntity lockOwnDesign(UUID designId, UUID actor, DesignProjectJpaEntity project) {
        DesignDraftJpaEntity draft = drafts.lockById(designId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        entityManager.refresh(draft);
        CustomerDesignRules.requireOwner(actor, draft.getOwnerUserId(), draft.getStatus());
        if (!project.getCustomerId().equals(draft.getCustomerId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return draft;
    }

    private ProjectView view(DesignProjectJpaEntity p) {
        return new ProjectView(p.getId(), p.getName(), p.getDescription(), p.getStatus(), p.getVersion(),
                p.getCreatedAt(), p.getArchivedAt(), views.of(drafts.inProject(p.getId())),
                assets.countByProjectId(p.getId()));
    }

    void record(DesignProjectJpaEntity project, UUID actor, ProjectEventType type, String notes) {
        history.save(new DesignProjectEventJpaEntity(project.getId(), actor, type.name(), notes));
    }

    private void decide(DesignDraftJpaEntity draft, UUID actor, DesignDecisionType type, String notes) {
        decisions.save(new DesignDecisionJpaEntity(draft, actor, type.name(), notes));
    }

    /** The customer whose own login {@code actor} is, while the customer may take on new work (BR-01). */
    private CustomerSummary activeCustomerOf(UUID actor) {
        authenticated(actor);
        CustomerSummary customer = customers.findByUserId(actor).orElseThrow(() ->
                new BusinessException(ErrorCode.FORBIDDEN, "Only a customer keeps design projects"));
        if (!"ACTIVE".equals(customer.status())) {
            throw new BusinessException(ErrorCode.CUSTOMER_NOT_ACTIVE,
                    "The customer is " + customer.status() + "; only an active customer starts a project");
        }
        return customer;
    }

    private static ProjectStatus parseStatus(String status) {
        try {
            return ProjectStatus.valueOf(status.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "status must be ACTIVE or ARCHIVED");
        }
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    private static void authenticated(UUID actor) {
        if (actor == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }
}
