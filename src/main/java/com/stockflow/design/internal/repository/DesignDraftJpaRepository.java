package com.stockflow.design.internal.repository;

import com.stockflow.common.persistence.BaseJpaRepository;
import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link DesignDraftJpaEntity}. {@link #lockById} and {@code findById} return any row; their
 * callers decide who may see it ({@code CustomerDesignRules.requireReader}).
 */
public interface DesignDraftJpaRepository extends BaseJpaRepository<DesignDraftJpaEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DesignDraftJpaEntity d where d.id = :id")
    Optional<DesignDraftJpaEntity> lockById(@Param("id") UUID id);

    /**
     * Whether a quote request on the design is open: what lets the sales staff read it (plan D7). Asked by a read only
     * when that decides - a reader holding the right to read quote requests, of a design that is neither theirs nor
     * waiting for them as a reviewer.
     */
    @Query("select count(r) > 0 from DesignQuoteRequestJpaEntity r where r.draftId = :id "
            + "and r.status = com.stockflow.design.internal.domain.QuoteRequestStatus.OPEN")
    boolean hasOpenQuoteRequest(@Param("id") UUID id);

    /** A user's own designs in the given statuses (BR-03: a design is its owner's alone). */
    @Query("select d from DesignDraftJpaEntity d where d.status in :statuses and d.ownerUserId = :userId")
    Page<DesignDraftJpaEntity> findOwnedBy(@Param("userId") UUID userId,
            @Param("statuses") Collection<DesignStatus> statuses, Pageable pageable);

    /**
     * The Sales Manager's queue (kltn-docs 12 §4.4): customers' designs submitted on their own and waiting for review,
     * the longest waiting first. A design waiting in a project's review round is that round's, one item of the queue
     * of rounds (§4.10).
     */
    @Query(value = "select d from DesignDraftJpaEntity d where d.templateId is not null and "
            + "d.status = com.stockflow.design.internal.domain.DesignStatus.PENDING_REVIEW and not exists ("
            + "select 1 from DesignReviewRoundDesignJpaEntity m where m.draftId = d.id and m.outcome is null) "
            + "order by d.reviewRequestedAt asc, d.id asc",
            countQuery = "select count(d) from DesignDraftJpaEntity d where d.templateId is not null and "
            + "d.status = com.stockflow.design.internal.domain.DesignStatus.PENDING_REVIEW and not exists ("
            + "select 1 from DesignReviewRoundDesignJpaEntity m where m.draftId = d.id and m.outcome is null)")
    Page<DesignDraftJpaEntity> reviewQueue(Pageable pageable);

    /** A project's designs, archived ones aside, the oldest first: the project's view, side by side (§4.9 step 30). */
    @Query("select d from DesignDraftJpaEntity d where d.projectId = :projectId "
            + "and d.status <> com.stockflow.design.internal.domain.DesignStatus.ARCHIVED order by d.createdAt, d.id")
    java.util.List<DesignDraftJpaEntity> inProject(@Param("projectId") UUID projectId);

    /**
     * A project's designs, archived ones aside, locked in id order - the order a batch locks them in - so that closing
     * the project sees every submission that committed before it (BR-22).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DesignDraftJpaEntity d where d.projectId = :projectId "
            + "and d.status <> com.stockflow.design.internal.domain.DesignStatus.ARCHIVED order by d.id")
    java.util.List<DesignDraftJpaEntity> lockInProject(@Param("projectId") UUID projectId);

    /** What the 20-design limit counts (B42): a project's designs, archived ones aside. */
    @Query("select count(d) from DesignDraftJpaEntity d where d.projectId = :projectId "
            + "and d.status <> com.stockflow.design.internal.domain.DesignStatus.ARCHIVED")
    long countInProject(@Param("projectId") UUID projectId);

    /** Whether a design of the project waits for review: the project cannot close meanwhile (BR-22). */
    boolean existsByProjectIdAndStatus(UUID projectId, DesignStatus status);

    /** What the draft limit counts (B37). */
    long countByCustomerIdAndStatus(UUID customerId, DesignStatus status);

    /**
     * Serialises the transactions that start a draft for one customer until they end, so two tabs creating at once
     * cannot both see 49 drafts and make 51. Wrapped in a sub-select because {@code pg_advisory_xact_lock} returns
     * {@code void}.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS locked",
            nativeQuery = true)
    Integer lockCustomerDrafts(@Param("key") String key);
}
