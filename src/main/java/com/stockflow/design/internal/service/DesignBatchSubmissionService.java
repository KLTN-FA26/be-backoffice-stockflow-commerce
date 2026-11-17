package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.contracts.DesignBatchSubmittedForReview;
import com.stockflow.design.internal.domain.DesignProjectRules;
import com.stockflow.design.internal.domain.LayoutPreflight;
import com.stockflow.design.internal.domain.ProjectEventType;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.design.internal.entity.DesignProjectJpaEntity;
import com.stockflow.design.internal.entity.DesignReviewRoundDesignJpaEntity;
import com.stockflow.design.internal.entity.DesignReviewRoundJpaEntity;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignReviewRoundDesignJpaRepository;
import com.stockflow.design.internal.repository.DesignReviewRoundJpaRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Submits several drafts of a design project together (kltn-docs 12 §4.10; BR-05, BR-19): all of them or none. Each is
 * checked as a design submitted alone is ({@link DesignSubmissionService}: the preflight, its files, its print file); if
 * any does not pass, none goes, each failing design's history says why ({@code PREFLIGHT_FAILED}) and so does the
 * project's ({@code BATCH_PREFLIGHT_FAILED}). If all pass, they wait for review together in one round - one item of the
 * Sales Manager's queue, announced once ({@link DesignBatchSubmittedForReview}).
 *
 * <p>The same three steps as a design alone: short reads, the print files with no transaction held, and one short
 * transaction that locks the project, then each design in id order, and submits them only if nothing moved.</p>
 */
@Service
public class DesignBatchSubmissionService {

    /**
     * The refusals of one design that a batch names in {@code details}: a design gone from the project (or never in it),
     * one no longer a draft, one at a stale version. Most serious first: the answer's error code is the first of these
     * any design of the batch got, as a design gone must leave the batch, one no longer a draft too, and a stale one
     * only needs reading again.
     */
    private static final List<ErrorCode> NAMED_REFUSALS = List.of(ErrorCode.NOT_FOUND, ErrorCode.DESIGN_NOT_DRAFT,
            ErrorCode.CONFLICT);

    private static final String GONE = "This design is not in the project";

    /** One design the batch cannot submit as it is, and why. */
    private record Refusal(UUID designId, BusinessException cause) {
    }

    private final DesignProjectService projects;
    private final DesignSubmissionService submissions;
    private final DesignDraftJpaRepository drafts;
    private final DesignReviewRoundJpaRepository rounds;
    private final DesignReviewRoundDesignJpaRepository members;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;
    private final TransactionOperations tx;
    private final Clock clock;

    public DesignBatchSubmissionService(DesignProjectService projects, DesignSubmissionService submissions,
                                        DesignDraftJpaRepository drafts, DesignReviewRoundJpaRepository rounds,
                                        DesignReviewRoundDesignJpaRepository members, ApplicationEventPublisher events,
                                        EntityManager entityManager, TransactionOperations tx, Clock clock) {
        this.projects = projects;
        this.submissions = submissions;
        this.drafts = drafts;
        this.rounds = rounds;
        this.members = members;
        this.events = events;
        this.entityManager = entityManager;
        this.tx = tx;
        this.clock = clock;
    }

    /** One design of a batch, at the version its owner saw (BR-11). */
    public record DesignAtVersion(UUID id, long version) {
    }

    /**
     * Submits {@code designs} of project {@code projectId} together.
     *
     * @return the review round they wait in
     * @throws BusinessException 422 {@code DESIGN_BATCH_PREFLIGHT_FAILED} with {@code details.designs} - each
     *         {@code {designId, issues?, roles?}} - when any does not pass; 400 for fewer than
     *         {@value DesignProjectRules#MIN_BATCH} designs or one named twice; 409 {@code DESIGN_PROJECT_ARCHIVED};
     *         404 {@code NOT_FOUND} for a design gone from the project or never in it, 409 {@code DESIGN_NOT_DRAFT},
     *         409 {@code CONFLICT} for a stale version: every design of the batch refused is named, whichever step met
     *         it (FE request 2026-11-17 S-1..S-3) - {@code details.designs} lists each as {@code {designId, errorCode}},
     *         {@code details.designIds} the designs of the answer's own code (see {@link #NAMED_REFUSALS}); 404 for
     *         anyone but the owner of the project
     */
    @Auditable(action = AuditAction.UPDATE, resourceType = "design-project", resourceId = "#projectId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UUID submit(UUID projectId, UUID actor, List<DesignAtVersion> designs) {
        if (designs == null || designs.size() < DesignProjectRules.MIN_BATCH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "A batch is at least " + DesignProjectRules.MIN_BATCH + " drafts; submit one alone instead");
        }
        if (new HashSet<>(designs.stream().map(DesignAtVersion::id).toList()).size() != designs.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A design is named twice in the batch");
        }
        Map<UUID, DesignSubmissionService.Plan> plans = tx.execute(status -> {
            DesignProjectJpaEntity project = projects.own(projectId, actor);
            DesignProjectRules.requireActive(project.getStatus());
            Map<UUID, DesignSubmissionService.Plan> found = new LinkedHashMap<>();
            // Every design refused is reported, each with its own reason, not only the first one met: the customer has
            // to know which designs to open again or take out (FE requests 2026-11-15 BE-4, 2026-11-17 S-1, S-3).
            List<Refusal> refused = new ArrayList<>();
            for (DesignAtVersion design : designs) {
                try {
                    requireInProject(design.id(), projectId);
                    found.put(design.id(), submissions.plan(design.id(), actor, design.version(), null));
                } catch (BusinessException e) {
                    if (!NAMED_REFUSALS.contains(e.errorCode())) {
                        throw e;
                    }
                    refused.add(new Refusal(design.id(), e));
                }
            }
            if (!refused.isEmpty()) {
                throw refusal(refused, designs.size());
            }
            return found;
        });

        List<Map<String, Object>> failures = new ArrayList<>();
        Map<UUID, List<LayoutPreflight.Issue>> failedPreflight = new LinkedHashMap<>();
        plans.forEach((id, plan) -> {
            List<LayoutPreflight.Issue> issues = new ArrayList<>(plan.issues());
            if (issues.isEmpty() && plan.outdatedFiles().isEmpty()) {
                issues.addAll(submissions.printFileIssues(plan));
            }
            if (!issues.isEmpty()) {
                failedPreflight.put(id, issues);
                failures.add(Map.of("designId", id.toString(), "issues", LayoutPreflight.asMaps(issues)));
            } else if (!plan.outdatedFiles().isEmpty()) {
                failures.add(Map.of("designId", id.toString(), "roles", plan.outdatedFiles()));
            }
        });
        if (!failures.isEmpty()) {
            tx.executeWithoutResult(status -> {
                failedPreflight.forEach((id, issues) -> submissions.recordFailure(id, actor, issues));
                projects.record(projects.own(projectId, actor), actor, ProjectEventType.BATCH_PREFLIGHT_FAILED,
                        failures.stream().map(f -> f.get("designId") + ": " + (f.containsKey("issues")
                                ? failedPreflight.get(UUID.fromString((String) f.get("designId"))).stream()
                                .map(LayoutPreflight.Issue::rule).distinct().collect(Collectors.joining(", "))
                                : "files to render again " + f.get("roles"))).collect(Collectors.joining("; ")));
            });
            throw new BusinessException(ErrorCode.DESIGN_BATCH_PREFLIGHT_FAILED,
                    failures.size() + " of " + designs.size() + " designs did not pass; none was submitted", List.of(),
                    Map.of("designs", failures));
        }
        return tx.execute(status -> submitLocked(projectId, actor, designs, plans));
    }

    /** Under the project's lock, then each design's in id order: all submitted in one round, if nothing moved. */
    private UUID submitLocked(UUID projectId, UUID actor, List<DesignAtVersion> designs,
                              Map<UUID, DesignSubmissionService.Plan> plans) {
        DesignProjectJpaEntity project = projects.lockActive(projectId, actor);
        Instant now = clock.instant();
        List<DesignAtVersion> ordered = designs.stream().sorted(Comparator.comparing(DesignAtVersion::id)).toList();
        // Every design locked and checked before any is submitted: one that moved between the first read and now
        // (someone else saved, submitted or moved it) is named with the others, not thrown bare (FE request 2026-11-17
        // S-2).
        Map<UUID, DesignDraftJpaEntity> locked = new LinkedHashMap<>();
        List<Refusal> refused = new ArrayList<>();
        for (DesignAtVersion design : ordered) {
            try {
                DesignDraftJpaEntity draft = drafts.lockById(design.id())
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, GONE));
                entityManager.refresh(draft);
                if (!projectId.equals(draft.getProjectId())) {
                    throw new BusinessException(ErrorCode.NOT_FOUND, GONE);
                }
                DesignSubmissionService.requireSubmittable(draft, design.version());
                locked.put(design.id(), draft);
            } catch (BusinessException e) {
                if (!NAMED_REFUSALS.contains(e.errorCode())) {
                    throw e;
                }
                refused.add(new Refusal(design.id(), e));
            }
        }
        if (!refused.isEmpty()) {
            throw refusal(refused, designs.size());
        }
        DesignReviewRoundJpaEntity round = rounds.saveAndFlush(new DesignReviewRoundJpaEntity(projectId, actor, now));
        for (DesignAtVersion design : ordered) {
            submissions.submitOne(locked.get(design.id()), actor, design.version(), plans.get(design.id()),
                    round.getId());
            members.save(new DesignReviewRoundDesignJpaEntity(round.getId(), design.id()));
        }
        members.flush();
        List<UUID> ids = designs.stream().map(DesignAtVersion::id).toList();
        projects.record(project, actor, ProjectEventType.BATCH_SUBMITTED, "Round " + round.getId() + ": "
                + ids.stream().map(UUID::toString).collect(Collectors.joining(", ")));
        events.publishEvent(new DesignBatchSubmittedForReview(round.getId(), projectId, project.getCustomerId(), ids,
                now));
        return round.getId();
    }

    private void requireInProject(UUID designId, UUID projectId) {
        DesignDraftJpaEntity draft = drafts.findById(designId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, GONE));
        if (!projectId.equals(draft.getProjectId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, GONE);
        }
    }

    /**
     * The answer to a batch some of whose designs cannot go: the code of the most serious refusal; {@code designIds},
     * the designs refused for that code (as before, so a client reading only that keeps working); {@code designs},
     * every design refused with its own code.
     */
    private static BusinessException refusal(List<Refusal> refused, int batchSize) {
        Refusal first = refused.stream()
                .min(Comparator.comparingInt(r -> NAMED_REFUSALS.indexOf(r.cause().errorCode()))).orElseThrow();
        ErrorCode code = first.cause().errorCode();
        List<String> sameCode = refused.stream().filter(r -> r.cause().errorCode() == code)
                .map(r -> r.designId().toString()).toList();
        List<Map<String, Object>> each = refused.stream()
                .map(r -> Map.<String, Object>of("designId", r.designId().toString(), "errorCode",
                        r.cause().errorCode().name()))
                .toList();
        String message = refused.size() == 1 ? first.cause().getMessage()
                : refused.size() + " of " + batchSize + " designs cannot be submitted as they are; none was submitted";
        return new BusinessException(code, message, List.of(), Map.of("designIds", sameCode, "designs", each));
    }
}
