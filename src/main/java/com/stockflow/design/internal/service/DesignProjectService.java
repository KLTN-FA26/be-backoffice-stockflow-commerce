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
import com.stockflow.design.internal.entity.DesignDecisionJpaEntity;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.entity.DesignProjectEventJpaEntity;
import com.stockflow.design.internal.entity.DesignProjectJpaEntity;
import com.stockflow.design.internal.repository.DesignDecisionJpaRepository;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectAssetJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectEventJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectJpaRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A customer's design projects (kltn-docs 12 §4.9, §5.4; BR-17, BR-22; B42): created, renamed and closed by their owner,
 * holding designs of that customer - at most {@value DesignProjectRules#MAX_DESIGNS}, archived ones not counted. A
 * design joins when it is started in the project, when its owner adds it, or when it is a revision of a design in an
 * open project; it leaves when its owner removes it, never while it waits for review. Joining or leaving changes
 * nothing else about the design.
 *
 * <p>Only the owner sees a project (BR-17); the Sales Manager and the sales staff see its name alone, on a review round
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
    private final CustomerService customers;
    private final DraftViews views;
    private final EntityManager entityManager;
    private final Clock clock;

    public DesignProjectService(DesignProjectJpaRepository projects, DesignDraftJpaRepository drafts,
                                DesignProjectAssetJpaRepository assets, DesignProjectEventJpaRepository history,
                                DesignDecisionJpaRepository decisions, CustomerService customers, DraftViews views,
                                EntityManager entityManager, Clock clock) {
        this.projects = projects;
        this.drafts = drafts;
        this.assets = assets;
        this.history = history;
        this.decisions = decisions;
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
        return projects.findOwnedBy(actor, statuses, Pages.of(page.getPageNumber(), page.getPageSize()))
                .map(p -> new ProjectSummary(p.getId(), p.getName(), p.getDescription(), p.getStatus(), p.getVersion(),
                        drafts.countInProject(p.getId()), p.getCreatedAt(), p.getArchivedAt()));
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
        project.archive(clock.instant());
        projects.saveAndFlush(project);
        record(project, actor, ProjectEventType.PROJECT_ARCHIVED, project.getName());
        return view(project);
    }

    /**
     * Adds one of the owner's designs (§4.9 step 28, BR-17): not archived, in no other project, of the project's
     * customer. Adding a design already in it changes nothing.
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project", resourceId = "#id")
    public ProjectView addDesign(UUID id, UUID actor, UUID designId) {
        DesignProjectJpaEntity project = lockActive(id, actor);
        DesignDraftJpaEntity draft = lockOwnDesign(designId, actor, project);
        if (id.equals(draft.getProjectId())) {
            return view(project);
        }
        DesignProjectRules.requireJoinable(draft.getStatus(), draft.getProjectId());
        join(project, draft, actor);
        return view(project);
    }

    /** Removes a design from the project (§4.9 step 28): never one waiting for review (BR-17). */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project", resourceId = "#id")
    public ProjectView removeDesign(UUID id, UUID actor, UUID designId) {
        DesignProjectJpaEntity project = lockActive(id, actor);
        DesignDraftJpaEntity draft = lockOwnDesign(designId, actor, project);
        if (!id.equals(draft.getProjectId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        DesignProjectRules.requireNotPending(draft.getStatus());
        draft.leaveProject();
        drafts.saveAndFlush(draft);
        decide(draft, actor, DesignDecisionType.REMOVED_FROM_PROJECT, "Project " + id);
        return view(project);
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
     * A design just started in the project (§4.1 step 3), in the caller's transaction: the project open, the owner's,
     * of the design's customer, with room.
     */
    void joinOnStart(DesignDraftJpaEntity draft, UUID projectId, UUID actor) {
        DesignProjectJpaEntity project = lockActive(projectId, actor);
        if (!project.getCustomerId().equals(draft.getCustomerId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        join(project, draft, actor);
    }

    /**
     * A revision joins the project of the design it revises while that project is open (§4.7, BR-22); a closed
     * project takes it not, and it stands on its own.
     */
    void joinRevision(DesignDraftJpaEntity revision, UUID sourceProjectId, UUID actor) {
        DesignProjectJpaEntity project = projects.lockById(sourceProjectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        entityManager.refresh(project);
        if (project.getStatus() == ProjectStatus.ACTIVE) {
            join(project, revision, actor);
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

    private DesignProjectJpaEntity lock(UUID id, UUID actor) {
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

    private void join(DesignProjectJpaEntity project, DesignDraftJpaEntity draft, UUID actor) {
        DesignProjectRules.requireRoomForDesign(drafts.countInProject(project.getId()));
        draft.joinProject(project.getId());
        drafts.saveAndFlush(draft);
        decide(draft, actor, DesignDecisionType.ADDED_TO_PROJECT, "Project " + project.getId());
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
