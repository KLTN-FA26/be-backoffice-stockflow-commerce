package com.stockflow.design.internal.service;

import com.stockflow.common.error.BusinessException;
import com.stockflow.common.error.ErrorCode;
import com.stockflow.design.internal.domain.ProjectEventType;
import com.stockflow.design.internal.entity.DesignProjectEventJpaEntity;
import com.stockflow.design.internal.entity.DesignReviewRoundDesignJpaEntity;
import com.stockflow.design.internal.entity.DesignReviewRoundJpaEntity;
import com.stockflow.design.internal.repository.DesignProjectEventJpaRepository;
import com.stockflow.design.internal.repository.DesignReviewRoundDesignJpaRepository;
import com.stockflow.design.internal.repository.DesignReviewRoundJpaRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * What a decision on a design does to the project review round it waits in, if any (kltn-docs 12 BR-20): records the
 * outcome, and closes the round once every design in it has one ({@code BATCH_CLOSED} in the project's history). Runs
 * in the decision's own transaction, after the design's lock and under the round's, so two decisions in one round are
 * counted one after the other.
 */
@Component
class DesignReviewRounds {

    private final DesignReviewRoundJpaRepository rounds;
    private final DesignReviewRoundDesignJpaRepository members;
    private final DesignProjectEventJpaRepository history;
    private final EntityManager entityManager;

    DesignReviewRounds(DesignReviewRoundJpaRepository rounds, DesignReviewRoundDesignJpaRepository members,
                       DesignProjectEventJpaRepository history, EntityManager entityManager) {
        this.rounds = rounds;
        this.members = members;
        this.history = history;
        this.entityManager = entityManager;
    }

    /** {@code designId} was {@code outcome} ({@code APPROVED} or {@code RETURNED}) by {@code actor} at {@code at}. */
    void decided(UUID designId, String outcome, UUID actor, Instant at) {
        DesignReviewRoundDesignJpaEntity member = members.waiting(designId).orElse(null);
        if (member == null) {
            return;
        }
        DesignReviewRoundJpaEntity round = rounds.lockById(member.getRoundId())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        entityManager.refresh(round);
        member.decide(outcome, at);
        members.saveAndFlush(member);
        List<DesignReviewRoundDesignJpaEntity> all = members.findByRoundId(round.getId());
        if (all.stream().allMatch(m -> m.getOutcome() != null)) {
            round.close(at);
            rounds.saveAndFlush(round);
            history.save(new DesignProjectEventJpaEntity(round.getProjectId(), actor,
                    ProjectEventType.BATCH_CLOSED.name(), "Round " + round.getId() + ": " + all.stream()
                    .sorted(Comparator.comparing(DesignReviewRoundDesignJpaEntity::getDraftId))
                    .map(m -> m.getDraftId() + " " + m.getOutcome()).collect(Collectors.joining("; "))));
        }
    }
}
