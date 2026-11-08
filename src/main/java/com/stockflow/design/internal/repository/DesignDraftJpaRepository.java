package com.stockflow.design.internal.repository;

import com.stockflow.design.internal.domain.DesignStatus;
import com.stockflow.design.internal.entity.DesignDraftJpaEntity;
import com.stockflow.common.persistence.BaseJpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link DesignDraftJpaEntity}. An archived design is gone for everyone (kltn-docs 12 BR-03,
 * BR-16), and a design a customer started (it has a template) is its owner's alone (B38): the reads that take a user
 * leave both out. {@link #lockById} and {@code findById} return any row; their callers decide who may see it.
 */
public interface DesignDraftJpaRepository extends BaseJpaRepository<DesignDraftJpaEntity> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DesignDraftJpaEntity d where d.id = :id")
    Optional<DesignDraftJpaEntity> lockById(@Param("id") UUID id);

    /** The designs a user works on, in the given statuses. */
    @Query("select d from DesignDraftJpaEntity d where d.status in :statuses and "
            + "(d.ownerUserId = :userId or (d.templateId is null and "
            + "(d.assignedUserId = :userId or d.reviewerUserId = :userId)))")
    Page<DesignDraftJpaEntity> findForUser(@Param("userId") UUID userId,
            @Param("statuses") Collection<DesignStatus> statuses, Pageable pageable);

    // Single-owner scope cannot express owner OR assigned designer. ALL/WAREHOUSE grants do
    // not bypass this explicit predicate for private customer files.
    @Query("select d from DesignDraftJpaEntity d where d.id = :id and "
            + "d.status <> com.stockflow.design.internal.domain.DesignStatus.ARCHIVED and "
            + "(d.ownerUserId = :userId or (d.templateId is null and "
            + "(d.assignedUserId = :userId or d.reviewerUserId = :userId)))")
    Optional<DesignDraftJpaEntity> findAccessible(@Param("id") UUID id, @Param("userId") UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DesignDraftJpaEntity d where d.id = :id and "
            + "d.status <> com.stockflow.design.internal.domain.DesignStatus.ARCHIVED and "
            + "(d.ownerUserId = :userId or (d.templateId is null and d.assignedUserId = :userId))")
    Optional<DesignDraftJpaEntity> lockAccessible(@Param("id") UUID id, @Param("userId") UUID userId);

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
