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
     * Whether a quote request on the design is open: what lets the sales staff read it (plan D7). Asked here, beside
     * the design, by every read of one.
     */
    @Query("select count(r) > 0 from DesignQuoteRequestJpaEntity r where r.draftId = :id "
            + "and r.status = com.stockflow.design.internal.domain.QuoteRequestStatus.OPEN")
    boolean hasOpenQuoteRequest(@Param("id") UUID id);

    /** A user's own designs in the given statuses (BR-03: a design is its owner's alone). */
    @Query("select d from DesignDraftJpaEntity d where d.status in :statuses and d.ownerUserId = :userId")
    Page<DesignDraftJpaEntity> findOwnedBy(@Param("userId") UUID userId,
            @Param("statuses") Collection<DesignStatus> statuses, Pageable pageable);

    /**
     * The Sales Manager's queue (kltn-docs 12 §4.4): customers' designs waiting for review, the longest waiting first.
     */
    @Query(value = "select d from DesignDraftJpaEntity d where d.templateId is not null and "
            + "d.status = com.stockflow.design.internal.domain.DesignStatus.PENDING_REVIEW "
            + "order by d.reviewRequestedAt asc, d.id asc",
            countQuery = "select count(d) from DesignDraftJpaEntity d where d.templateId is not null and "
            + "d.status = com.stockflow.design.internal.domain.DesignStatus.PENDING_REVIEW")
    Page<DesignDraftJpaEntity> reviewQueue(Pageable pageable);

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
