package com.stockflow.design.internal.service;

import com.stockflow.common.audit.AuditAction;
import com.stockflow.common.audit.Auditable;
import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.common.persistence.Pages;
import com.stockflow.customer.api.CustomerService;
import com.stockflow.design.internal.entity.DesignProjectJpaEntity;
import com.stockflow.design.internal.entity.DesignReviewRoundDesignJpaEntity;
import com.stockflow.design.internal.entity.DesignReviewRoundJpaEntity;
import com.stockflow.design.internal.repository.DesignDraftJpaRepository;
import com.stockflow.design.internal.repository.DesignProjectJpaRepository;
import com.stockflow.design.internal.repository.DesignReviewRoundDesignJpaRepository;
import com.stockflow.design.internal.repository.DesignReviewRoundJpaRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Sales Manager's side of projects' review rounds (kltn-docs 12 §4.10; BR-20): the queue of rounds, each one item;
 * a round opened with its designs side by side; and "Duyệt tất cả", which approves each design still waiting in turn -
 * one that cannot be approved stays waiting and stops none of the others. Each design is decided on its own
 * ({@link DesignReviewService}), and the round closes once every one is ({@link DesignReviewRounds}).
 *
 * <p>The Sales Manager sees the project's name as context and nothing else of it (BR-17).</p>
 */
@Service
@Transactional(readOnly = true)
public class DesignReviewRoundService {

    private final DesignReviewRoundJpaRepository rounds;
    private final DesignReviewRoundDesignJpaRepository members;
    private final DesignProjectJpaRepository projects;
    private final DesignDraftJpaRepository drafts;
    private final DesignReviewService reviews;
    private final CustomerService customers;
    private final DraftViews views;

    public DesignReviewRoundService(DesignReviewRoundJpaRepository rounds, DesignReviewRoundDesignJpaRepository members,
                                    DesignProjectJpaRepository projects, DesignDraftJpaRepository drafts,
                                    DesignReviewService reviews, CustomerService customers, DraftViews views) {
        this.rounds = rounds;
        this.members = members;
        this.projects = projects;
        this.drafts = drafts;
        this.reviews = reviews;
        this.customers = customers;
        this.views = views;
    }

    /** One design of a round: the design as it is now, and the decision on it, null while it waits. */
    public record RoundDesign(DesignWorkflowService.DraftView design, String outcome, Instant decidedAt) {
    }

    /** A round as the Sales Manager reads it. */
    public record RoundView(UUID id, UUID projectId, String projectName, UUID customerId, String customerName,
                            UUID submittedBy, Instant submittedAt, Instant closedAt, List<RoundDesign> designs) {
    }

    /** What "Duyệt tất cả" did to one design: {@code APPROVED}, or why it was not. */
    public record ApprovalOutcome(UUID designId, String outcome, String errorCode, String message) {
    }

    /** The rounds waiting, the longest waiting first. */
    public Page<RoundView> queue(Pageable page) {
        Page<DesignReviewRoundJpaEntity> found = rounds.open(Pages.of(page.getPageNumber(), page.getPageSize()));
        return new PageImpl<>(views(found.getContent()), found.getPageable(), found.getTotalElements());
    }

    public RoundView get(UUID roundId) {
        return views(List.of(rounds.findById(roundId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND))))
                .getFirst();
    }

    /**
     * Approves each of {@code designs} - the round's designs at the versions the Sales Manager saw - that still waits,
     * one after the other, each in a step of its own (BR-09, BR-11). A design that cannot be approved stays waiting,
     * and its reason is in the outcome.
     */
    @Auditable(action = AuditAction.APPROVE, resourceType = "design-review-round", resourceId = "#roundId")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<ApprovalOutcome> approveAll(UUID roundId, UUID actor, List<DesignBatchSubmissionService.DesignAtVersion>
            designs) {
        Set<UUID> waiting = members.findByRoundId(roundId).stream().filter(m -> m.getOutcome() == null)
                .map(DesignReviewRoundDesignJpaEntity::getDraftId).collect(Collectors.toSet());
        if (rounds.findById(roundId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        List<ApprovalOutcome> outcomes = new ArrayList<>();
        for (var design : designs) {
            if (!waiting.contains(design.id())) {
                outcomes.add(new ApprovalOutcome(design.id(), "SKIPPED", ErrorCode.CONFLICT.name(),
                        "The design does not wait in this round"));
                continue;
            }
            try {
                reviews.decide(design.id(), actor, design.version(), true, null);
                outcomes.add(new ApprovalOutcome(design.id(), "APPROVED", null, null));
            } catch (BusinessException refused) {
                outcomes.add(new ApprovalOutcome(design.id(), "NOT_APPROVED", refused.errorCode().name(),
                        refused.getMessage()));
            }
        }
        return outcomes;
    }

    /** Rounds as read: their designs, projects and customers read once for the lot. */
    private List<RoundView> views(List<DesignReviewRoundJpaEntity> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<DesignReviewRoundDesignJpaEntity>> membersOf = members.findByRoundIdIn(found.stream()
                .map(DesignReviewRoundJpaEntity::getId).toList()).stream()
                .collect(Collectors.groupingBy(DesignReviewRoundDesignJpaEntity::getRoundId));
        Map<UUID, DesignWorkflowService.DraftView> designs = views.of(drafts.findAllById(membersOf.values().stream()
                        .flatMap(List::stream).map(DesignReviewRoundDesignJpaEntity::getDraftId).toList())).stream()
                .collect(Collectors.toMap(DesignWorkflowService.DraftView::id, Function.identity()));
        Map<UUID, DesignProjectJpaEntity> projectsById = projects.findAllById(found.stream()
                        .map(DesignReviewRoundJpaEntity::getProjectId).distinct().toList()).stream()
                .collect(Collectors.toMap(DesignProjectJpaEntity::getId, Function.identity()));
        Map<UUID, String> names = customers.fullNames(projectsById.values().stream()
                .map(DesignProjectJpaEntity::getCustomerId).collect(Collectors.toSet()));
        return found.stream().map(round -> {
            DesignProjectJpaEntity project = projectsById.get(round.getProjectId());
            List<RoundDesign> inRound = membersOf.getOrDefault(round.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(DesignReviewRoundDesignJpaEntity::getDraftId))
                    .map(m -> new RoundDesign(designs.get(m.getDraftId()), m.getOutcome(), m.getDecidedAt()))
                    .toList();
            return new RoundView(round.getId(), project.getId(), project.getName(), project.getCustomerId(),
                    names.get(project.getCustomerId()), round.getSubmittedBy(), round.getSubmittedAt(),
                    round.getClosedAt(), inRound);
        }).toList();
    }
}
